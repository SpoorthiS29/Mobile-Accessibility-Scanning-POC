package com.poc.a11y.atf;

import com.poc.a11y.atf.dto.AtfScanOutputDto;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shape B ATF scan:
 * <ol>
 *   <li>If the request already has a driver / session id, attach — do not launch.</li>
 *   <li>Otherwise (POC) start Appium and open the app once.</li>
 *   <li>Quit the Appium session and stop leftover UiAutomator2 processes so
 *       ATF can take the device's single UiAutomation slot. Do not
 *       force-stop the app.</li>
 *   <li>Run the on-device ATF harness against the current view. When
 *       {@code scroll} is true, scroll through viewports and merge findings;
 *       otherwise scan the current viewport only.</li>
 *   <li>Start a new UiAutomator2 session. If {@code closeApp} is true, quit
 *       that session.</li>
 * </ol>
 */
@Service
public class AtfMobileScanService {

    private static final Logger log = LoggerFactory.getLogger(AtfMobileScanService.class);

    private final AtfHarnessRunner harnessRunner;
    private final AtfResultMapper resultMapper;
    private final AppiumDriverManager appiumDriverManager;

    public AtfMobileScanService(AtfHarnessRunner harnessRunner,
                                AtfResultMapper resultMapper,
                                AppiumDriverManager appiumDriverManager) {
        this.harnessRunner = harnessRunner;
        this.resultMapper = resultMapper;
        this.appiumDriverManager = appiumDriverManager;
    }

    public ScanResult scanCurrentScreenWithRealAtf(ScanRequest request) {
        if (request.isVirtualDevice()) {
            return scanOnVirtualDevice(request);
        }
        return scanOnPhysicalDevice(request);
    }

    /**
     * Physical USB device: Appium launches (or attaches), then the session is
     * quit and leftover UiAutomator2 processes are force-stopped so ATF can
     * take the UiAutomation slot. Harness install / instrument / pull stay on
     * adb. After the scan a new UiAutomator2 session is started — the previous
     * driver is dead once the server was stopped.
     */
    private ScanResult scanOnPhysicalDevice(ScanRequest request) {
        try {
            positionAppThenReleaseUiAutomation(request);
            return toScanResult(request, harnessRunner.runScan(request));
        } finally {
            restorePhysicalUiAutomatorThenCloseApp(request);
        }
    }

    /**
     * Sauce Labs emulator: Appium installs + launches the app under test
     * first, then the harness only scans. After the scan, UiAutomator2 is
     * started again so the app can be closed through that session.
     */
    private ScanResult scanOnVirtualDevice(ScanRequest request) {
        validateVirtualRequest(request);
        AndroidDriver driver = null;
        try {
            log.info("Virtual device scan — launching app under test on Sauce Labs");
            driver = appiumDriverManager.startOnVirtualDevice(request);
            confirmLaunch(request, driver);
            return toScanResult(request, harnessRunner.runScanOnVirtualDevice(request, driver));
        } finally {
            restoreUiAutomatorThenCloseApp(request, driver);
        }
    }

    /**
     * Always restart UiAutomator2 after ATF. Close the app only through that
     * session when {@code closeApp} is true.
     */
    private void restoreUiAutomatorThenCloseApp(ScanRequest request, AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        appiumDriverManager.startUiAutomator(driver);
        if (request.isCloseApp()) {
            appiumDriverManager.closeAppiumSession(driver);
        }
    }

    /**
     * The driver used to launch / attach is gone after UiAutomation was
     * released. Start a fresh UiAutomator2 session; quit it when closeApp
     * is true.
     */
    private void restorePhysicalUiAutomatorThenCloseApp(ScanRequest request) {
        AndroidDriver restored = null;
        try {
            restored = appiumDriverManager.startUiAutomatorServer(request);
            if (request.isCloseApp()) {
                appiumDriverManager.terminateApp(restored, request.getAppPackage());
                appiumDriverManager.closeAppiumSession(restored);
            }
            // closeApp=false: leave restored session + AUT running
        } catch (Exception e) {
            log.warn("Could not start UiAutomator2 after ATF: {}", e.getMessage());
            if (request.isCloseApp() && restored != null) {
                appiumDriverManager.closeAppiumSession(restored);
            }
        }
    }

    private ScanResult toScanResult(ScanRequest request, AtfScanOutputDto rawResult) {
        List<Issue> issues = resultMapper.toIssues(rawResult);
        int elementsScanned = rawResult.getResultCount();
        return new ScanResult(
                request.getPlatformName(),
                request.getDeviceName(),
                elementsScanned,
                issues
        );
    }

    private static void validateVirtualRequest(ScanRequest request) {
        if (request.getSauceUsername() == null || request.getSauceUsername().isBlank()
                || request.getSauceAccessKey() == null || request.getSauceAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                    "virtualDevice=true requires sauceUsername and sauceAccessKey");
        }
        if (request.getAppPath() == null || request.getAppPath().isBlank()) {
            throw new IllegalArgumentException(
                    "virtualDevice=true requires appPath (local path to the APK under test)");
        }
        if (request.getDeviceName() == null || request.getDeviceName().isBlank()) {
            throw new IllegalArgumentException(
                    "virtualDevice=true requires deviceName (Sauce emulator name)");
        }
        if (request.getPlatformVersion() == null || request.getPlatformVersion().isBlank()) {
            throw new IllegalArgumentException(
                    "virtualDevice=true requires platformVersion (e.g. \"14.0\")");
        }
    }

    /**
     * Ensures the target UI is on screen, then drops the Appium session and
     * leftover UiAutomator2 processes so ATF can take the UiAutomation slot.
     * The application process is left in the foreground.
     */
    private void positionAppThenReleaseUiAutomation(ScanRequest request) {
        AndroidDriver driver = acquirePhysicalDriver(request);
        enrichPackageFromSession(request, driver);
        appiumDriverManager.stopUiAutomatorServer(request.getDeviceName());    }

    private AndroidDriver acquirePhysicalDriver(ScanRequest request) {
        AndroidDriver existing = request.getExistingAndroidDriver();
        if (existing != null) {
            log.info("Using AndroidDriver already on the request — not launching the app");
            return existing;
        }
        if (request.getAppiumSessionId() != null && !request.getAppiumSessionId().isBlank()) {
            log.info("Attaching to existing Appium session {} — not launching the app",
                    request.getAppiumSessionId());
            return appiumDriverManager.attach(request);
        }
        log.info("No existing driver/session — launching the app via Appium (POC)");
        AndroidDriver driver = appiumDriverManager.start(request);
        confirmLaunch(request, driver);
        return driver;
    }

    private void confirmLaunch(ScanRequest request, AndroidDriver driver) {
        AppiumDriverManager.sleepQuietly(request.getPostLaunchWaitSeconds());
        if (driver == null) {
            return;
        }
        try {
            log.info("App in foreground: package={} activity={}",
                    driver.getCurrentPackage(), driver.currentActivity());
        } catch (Exception e) {
            log.warn("Could not confirm foreground app: {}", e.getMessage());
        }
        enrichPackageFromSession(request, driver);
    }

    private void enrichPackageFromSession(ScanRequest request, AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            if (request.getAppPackage() == null || request.getAppPackage().isBlank()) {
                request.setAppPackage(driver.getCurrentPackage());
            }
            if (request.getAppActivity() == null || request.getAppActivity().isBlank()) {
                request.setAppActivity(driver.currentActivity());
            }
        } catch (Exception e) {
            log.warn("Could not read package/activity from Appium: {}", e.getMessage());
        }
    }
}
