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
 *   <li>Quit Appium to free UiAutomation; do not force-stop the app.</li>
 *   <li>Run the on-device ATF harness against the current view. When
 *       {@code scroll} is true, scroll through viewports and merge findings;
 *       otherwise scan the current viewport only.</li>
 *   <li>Leave the app running so a later script can re-attach.</li>
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
        positionAppThenReleaseUiAutomation(request);

        AtfScanOutputDto rawResult = harnessRunner.runScan(request);
        List<Issue> issues = resultMapper.toIssues(rawResult);
        int elementsScanned = rawResult.getResultCount();

        return new ScanResult(
                request.getPlatformName(),
                request.getDeviceName(),
                elementsScanned,
                issues
        );
    }

    /**
     * Ensures the target UI is on screen, then drops Appium so ATF can run.
     * The application process is left in the foreground.
     */
    private void positionAppThenReleaseUiAutomation(ScanRequest request) {
        AndroidDriver driver = null;
        try {
            if (request.getExistingDriver() != null) {
                log.info("Using AndroidDriver already on the request — not launching the app");
                driver = request.getExistingDriver();
            } else if (request.getAppiumSessionId() != null && !request.getAppiumSessionId().isBlank()) {
                log.info("Attaching to existing Appium session {} — not launching the app",
                        request.getAppiumSessionId());
                driver = appiumDriverManager.attach(request);
            } else {
                log.info("No existing driver/session — launching the app via Appium (POC)");
                driver = appiumDriverManager.start(request);
                sleepQuietly(request.getPostLaunchWaitSeconds());
                try {
                    log.info("App in foreground: package={} activity={}",
                            driver.getCurrentPackage(), driver.currentActivity());
                } catch (Exception e) {
                    log.warn("Could not confirm foreground app: {}", e.getMessage());
                }
            }

            enrichPackageFromSession(request, driver);
        } finally {
            appiumDriverManager.releaseUiAutomation(driver);
        }
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

    private void sleepQuietly(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
