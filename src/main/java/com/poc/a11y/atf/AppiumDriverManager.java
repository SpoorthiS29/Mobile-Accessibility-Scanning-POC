package com.poc.a11y.atf;

import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.Capabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a new Appium session (POC: launches the app) or attaches to an
 * existing one. {@link #releaseUiAutomationForPhysicalScan} quits the session
 * and force-stops leftover UiAutomator2 processes so ATF can take the
 * device's single UiAutomation slot, without force-stopping the app.
 */
@Component
public class AppiumDriverManager {

    private static final Logger log = LoggerFactory.getLogger(AppiumDriverManager.class);

    private final AtfProperties properties;
    private final SauceLabsClient sauceLabsClient;
    private final AdbCommandExecutor adb;

    public AppiumDriverManager(AtfProperties properties,
                               SauceLabsClient sauceLabsClient,
                               AdbCommandExecutor adb) {
        this.properties = properties;
        this.sauceLabsClient = sauceLabsClient;
        this.adb = adb;
    }

    public AndroidDriver start(ScanRequest request) {
        if (request.isVirtualDevice()) {
            return startOnVirtualDevice(request);
        }
        String appPackage = blankToNull(request.getAppPackage());
        String appActivity = blankToNull(request.getAppActivity());
        String appPath = blankToNull(request.getAppPath());

        if (appPath == null && appPackage == null) {
            throw new IllegalArgumentException(
                    "ScanRequest must supply existingDriver / appiumSessionId, or appPath / appPackage");
        }

        UiAutomator2Options options = baseOptions(request);
        options.setNoReset(true);
//        keepAppRunningAfterSession(options);
        options.setCapability("appium:autoLaunch", true);
        options.setCapability("appium:forceAppLaunch", true);

        if (appPath != null) {
            applyAppPathIfNeeded(options, request, appPath);
        }
        applyAppIdentity(options, request);

        log.info("Starting Appium session to launch app package={} activity={} appPath={}",
                appPackage, appActivity, appPath);

        AndroidDriver driver = new AndroidDriver(serverUrl(request), options);
        bringAppToForeground(driver, request);
        return driver;
    }

    /**
     * Sauce Labs emulator path: upload the APK under test, start a virtual
     * device, install that APK, and bring it to the foreground. The ATF
     * harness is not involved here — it only scans afterward.
     */
    public AndroidDriver startOnVirtualDevice(ScanRequest request) {
        String appPath = blankToNull(request.getAppPath());
        if (appPath == null) {
            throw new IllegalArgumentException(
                    "virtualDevice=true requires appPath (local path to the APK under test)");
        }
        Path apk = Paths.get(appPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(apk)) {
            throw new IllegalArgumentException("appPath does not exist: " + apk);
        }

        String storageApp = sauceLabsClient.uploadApp(request, apk, apk.getFileName().toString());

        UiAutomator2Options options = virtualBaseOptions(request);
        options.setNoReset(false);
        options.setCapability("appium:dontStopAppOnReset", true);
        options.setCapability("appium:autoLaunch", true);
        options.setCapability("appium:forceAppLaunch", true);

//        HashMap<String, Object> sauceOptions = new HashMap<>();
//// Check below for the available versions
//        sauceOptions.put("appiumVersion", "latest");
//        options.setCapability("sauce:options", sauceOptions);

//        options.setCapability("appiumVersion", "2.0.0");
        options.setApp(storageApp);
        applyAppIdentity(options, request);

        log.info("Starting Sauce Labs virtual session device={} platformVersion={} apk={}",
                request.getDeviceName(), request.getPlatformVersion(), apk.getFileName());

        AndroidDriver driver = new AndroidDriver(sauceLabsClient.onDemandUrl(request), options);
        bringAppToForeground(driver, request);
        return driver;
    }

    private UiAutomator2Options virtualBaseOptions(ScanRequest request) {
        int commandTimeout = Math.max(300, properties.getAmInstrumentTimeoutSeconds() + 120);
        UiAutomator2Options options = new UiAutomator2Options()
                .setAutomationName(request.getAutomationName())
                .setPlatformName(request.getPlatformName())
                .setDeviceName(request.getDeviceName())
                .setNewCommandTimeout(Duration.ofSeconds(commandTimeout));
        String platformVersion = blankToNull(request.getPlatformVersion());
        if (platformVersion != null) {
            options.setPlatformVersion(platformVersion);
        }
        Map<String, Object> sauceOptions = new LinkedHashMap<>();
        sauceOptions.put("username", request.getSauceUsername());
        sauceOptions.put("accessKey", request.getSauceAccessKey());
//        sauceOptions.put("appiumVersion", "2.11.0");
        String scanName = request.getAppName() == null || request.getAppName().isBlank()
                ? "ATF accessibility scan"
                : "ATF scan: " + request.getAppName();
        sauceOptions.put("name", scanName);
        options.setCapability("sauce:options", sauceOptions);
        return options;
    }

    /**
     * Explicitly opens the target app. Session create alone is not enough on
     * some devices when noReset is set.
     */
    public void bringAppToForeground(AndroidDriver driver, ScanRequest request) {
        String appPackage = blankToNull(request.getAppPackage());
        String appActivity = blankToNull(request.getAppActivity());
        if (appPackage == null) {
            log.warn("Cannot activate app — appPackage is empty");
            return;
        }

        try {
            if (appActivity != null) {
                String component = appActivity.contains("/")
                        ? appActivity
                        : appPackage + "/" + appActivity;
                log.info("Starting activity {}", component);
                driver.executeScript("mobile: startActivity", Map.of(
                        "intent", component,
                        "wait", true
                ));
            } else {
                log.info("Activating app {}", appPackage);
                driver.activateApp(appPackage);
            }
        } catch (Exception e) {
            log.warn("mobile: startActivity failed ({}), falling back to activateApp", e.getMessage());
            driver.activateApp(appPackage);
        }
    }

    /**
     * Reuses a live Appium session (real implementation already navigated).
     */
    public AndroidDriver attach(ScanRequest request) {
        String sessionId = request.getAppiumSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("appiumSessionId is required to attach");
        }
        URL url = serverUrl(request);
        UiAutomator2Options options = baseOptions(request);
        options.setNoReset(true);
//        keepAppRunningAfterSession(options);
        options.setCapability("appium:autoLaunch", false);

        return new AndroidDriver(url, options) {
            @Override
            protected void startSession(Capabilities capabilities) {
                setSessionId(sessionId.trim());
            }
        };
    }

    /**
     * Physical-device path: delete the Appium session so UiAutomator2 cannot
     * respawn and keep the UiAutomation slot, then force-stop leftover
     * server processes over adb. The app under test is left in the
     * foreground ({@code dontStopAppOnReset} / {@code shouldTerminateApp=false}).
     */
    public void releaseUiAutomationForPhysicalScan(AndroidDriver driver, String deviceSerial) {
        closeAppiumSession(driver);
        stopUiAutomatorProcesses(deviceSerial);
        sleepQuietly(1);
    }



    /**
     * Drops the UiAutomator2 instrumentation so ATF can run. Does not
     * {@code terminateApp} / {@code force-stop} the app under test.
     * Used on Sauce virtual devices where the HTTP session must stay open
     * for later {@code mobile: shell} / pull commands.
     */
    public void releaseUiAutomation(AndroidDriver driver) {
        if (driver == null) {
            return;
        }

        try {
            log.info("Stopping UiAutomator2 without terminating the app");
            executeShell(driver, "am", List.of("force-stop", "io.appium.uiautomator2.server.test"));
            sleepQuietly(1);
            executeShell(driver, "am", List.of("force-stop", "io.appium.uiautomator2.server"));
            sleepQuietly(2);
            log.info("UiAutomator2 server stopped; application under test remains running");
        } catch (Exception e) {
            log.warn("Could not stop UiAutomator2 server cleanly: {}", e.getMessage());
        }
    }

    /**
     * Restarts UiAutomator2 after ATF by issuing a session command. Used on
     * virtual devices where the original session is still alive.
     */
    public void startUiAutomator(AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            log.info("Starting UiAutomator2 after ATF scan");
            driver.getCurrentPackage();
            log.info("UiAutomator2 is running");
        } catch (Exception e) {
            log.warn("Could not start UiAutomator2: {}", e.getMessage());
        }
    }

    /**
     * Starts a brand-new UiAutomator2 / Appium session after ATF. The previous
     * driver is dead once the server was stopped to free UiAutomation; poking
     * it cannot bring the server back.
     */
    public AndroidDriver startNewUiAutomatorSession(ScanRequest request) {
        String deviceSerial = request.getDeviceName();
        stopHarnessInstrumentation(deviceSerial);
        stopUiAutomatorProcesses(deviceSerial);
        sleepQuietly(1);

        UiAutomator2Options options = baseOptions(request);
        options.setNoReset(true);
        keepAppRunningAfterSession(options);
        options.setCapability("appium:autoLaunch", false);
        options.setCapability("appium:forceAppLaunch", false);
        applyAppIdentity(options, request);

        log.info("Starting a new UiAutomator2 session after ATF (app stays in foreground)");
        AndroidDriver driver = new AndroidDriver(serverUrl(request), options);
        log.info("UiAutomator2 is running again, package={}", safeCurrentPackage(driver));
        return driver;
    }

    public void terminateApp(AndroidDriver driver, String appPackage) {
        if (driver == null) {
            return;
        }
        if (appPackage == null || appPackage.isBlank()) {
            log.warn("closeApp=true but appPackage is empty — cannot terminate");
            return;
        }
        try {
            log.info("closeApp=true — terminating {}", appPackage);
            driver.terminateApp(appPackage.trim());
        } catch (Exception e) {
            log.warn("Could not terminate app: {}", e.getMessage());
        }
    }

    public void closeAppiumSession(AndroidDriver driver) {
        if (driver == null) {
            return;
        }

        try {
            log.info("Closing Appium session");
            driver.quit();
        } catch (Exception e) {
            log.warn("Could not close Appium session: {}", e.getMessage());
        }
    }

    private UiAutomator2Options baseOptions(ScanRequest request) {
        String serial = request.getDeviceName();
        UiAutomator2Options options = new UiAutomator2Options()
                .setAutomationName(request.getAutomationName())
                .setPlatformName(request.getPlatformName())
                .setDeviceName(serial)
                .setNewCommandTimeout(Duration.ofSeconds(120));
        if (serial != null && !serial.isBlank()) {
            options.setUdid(serial);
        }
        return options;
    }

    private static void applyAppIdentity(UiAutomator2Options options, ScanRequest request) {
        String appPackage = blankToNull(request.getAppPackage());
        String appActivity = blankToNull(request.getAppActivity());
        if (appPackage != null) {
            options.setAppPackage(appPackage);
            options.setAppWaitPackage(appPackage);
        }
        if (appActivity != null) {
            options.setAppActivity(appActivity);
            options.setAppWaitActivity("*");
        }
    }

    /**
     * Sets {@code app} only when the device is missing the package or has a
     * different version than the APK at {@code appPath}.
     */
    private void applyAppPathIfNeeded(UiAutomator2Options options, ScanRequest request, String appPath) {
        Path apk = Paths.get(appPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(apk)) {
            options.setApp(appPath);
            return;
        }
        try {
            PackageIdentity desired = ApkManifestReader.read(apk);
            String pkg = blankToNull(request.getAppPackage());
            if (pkg == null) {
                pkg = desired.packageName();
            }
            PackageIdentity installed = readInstalled(request.getDeviceName(), pkg);
            long apkLastModified = Files.getLastModifiedTime(apk).toMillis();
            if (!PackageIdentity.needsInstall(installed, desired, apkLastModified)) {
                log.info("App {} {} already installed — skipping reinstall",
                        pkg, desired.displayVersion());
                return;
            }
            log.info("Installing {} {} (device had {})",
                    pkg,
                    desired.displayVersion(),
                    installed == null ? "nothing / unknown version" : installed.displayVersion());
            options.setApp(appPath);
            options.setCapability("appium:enforceAppInstall", true);
        } catch (RuntimeException | IOException e) {
            log.warn("Could not compare app versions, will install APK: {}", e.getMessage());
            options.setApp(appPath);
            options.setCapability("appium:enforceAppInstall", true);
        }
    }

    private PackageIdentity readInstalled(String deviceSerial, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        AdbCommandExecutor.Result result =
                adb.run(deviceSerial, List.of("shell", "dumpsys", "package", packageName), 30);
        if (!result.isSuccess()) {
            return null;
        }
        return PackageIdentity.fromDumpsys(result.output());
    }

    private static void keepAppRunningAfterSession(UiAutomator2Options options) {
        options.setCapability("appium:dontStopAppOnReset", true);
        options.setCapability("appium:shouldTerminateApp", false);
    }

    private void stopHarnessInstrumentation(String deviceSerial) {
        forceStopPackage(deviceSerial, properties.getHarnessTestPackage());
        forceStopPackage(deviceSerial, properties.getHarnessPackage());
    }

    /**
     * Frees the device's single UiAutomation slot by stopping UiAutomator2.
     * Does not quit the Appium session and does not touch the AUT.
     */
    public void stopUiAutomatorServer(String deviceSerial) {
        log.info("Stopping UiAutomator2 server only (app stays in foreground)");
        stopUiAutomatorProcesses(deviceSerial);
        sleepQuietly(1);
    }

    private void stopUiAutomatorProcesses(String deviceSerial) {
        forceStopPackage(deviceSerial, "io.appium.uiautomator2.server.test");
        forceStopPackage(deviceSerial, "io.appium.uiautomator2.server");
        for (int attempt = 0; attempt < 8; attempt++) {
            String pids = pidof(deviceSerial,
                    "io.appium.uiautomator2.server io.appium.uiautomator2.server.test");
            if (pids == null || pids.isBlank()) {
                log.info("UiAutomator2 processes are gone");
                return;
            }
            log.info("Waiting for UiAutomator2 to exit (still running: {})", pids.trim());
            sleepQuietly(1);
            forceStopPackage(deviceSerial, "io.appium.uiautomator2.server.test");
            forceStopPackage(deviceSerial, "io.appium.uiautomator2.server");
        }
        log.warn("UiAutomator2 process still appears to be running. "
                + "ATF may fail with UiAutomationService already registered.");
    }

    private void forceStopPackage(String deviceSerial, String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return;
        }
        try {
            adb.run(deviceSerial, List.of("shell", "am", "force-stop", packageName), 15);
        } catch (RuntimeException e) {
            log.warn("force-stop {} failed: {}", packageName, e.getMessage());
        }
    }

    private String pidof(String deviceSerial, String processNames) {
        try {
            AdbCommandExecutor.Result result = adb.run(deviceSerial,
                    List.of("shell", "sh", "-c", "pidof " + processNames + " || true"), 15);
            return result.output() == null ? "" : result.output().trim();
        } catch (RuntimeException e) {
            log.warn("pidof {} failed: {}", processNames, e.getMessage());
            return "";
        }
    }

    /**
     * Appium 3 / UiAutomator2: the only reliable way to bring the server back
     * after a force-stop is a new session. autoLaunch=false and no `app`
     * capability so the AUT is not stopped or relaunched.
     *
     * The previous AndroidDriver is dead and must not be used.
     */
    public AndroidDriver startUiAutomatorServer(ScanRequest request) {
        String deviceSerial = request.getDeviceName();
        // ATF's AndroidJUnitRunner also holds UiAutomation; make sure it is gone.
        stopHarnessInstrumentation(deviceSerial);

        UiAutomator2Options options = baseOptions(request);
        options.setNoReset(true);
        options.setCapability("appium:autoLaunch", false);
        options.setCapability("appium:forceAppLaunch", false);
        // do NOT set app / enforceAppInstall
        // do NOT set dontStopAppOnReset
        applyAppIdentity(options, request);

        log.info("Starting a new UiAutomator2 session (autoLaunch=false, app stays as-is)");
        AndroidDriver driver = new AndroidDriver(serverUrl(request), options);
        log.info("UiAutomator2 is running again, foreground package={}", safeCurrentPackage(driver));
        return driver;
    }

    private static String safeCurrentPackage(AndroidDriver driver) {
        try {
            return driver.getCurrentPackage();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String executeShell(AndroidDriver driver, String command, List<String> args) {
        Object raw = driver.executeScript("mobile: shell", Map.of(
                "command", command,
                "args", args
        ));
        return raw == null ? "" : String.valueOf(raw);
    }

    private URL serverUrl(ScanRequest request) {
        String raw = request.getAppiumServerUrl();
        if (raw == null || raw.isBlank()) {
            raw = properties.getAppiumServerUrl();
        }
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Appium server URL: " + raw, e);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    static void sleepQuietly(int seconds) {
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
