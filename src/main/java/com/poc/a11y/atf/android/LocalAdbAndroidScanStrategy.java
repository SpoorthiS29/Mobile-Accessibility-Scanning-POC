package com.poc.a11y.atf.android;

import com.poc.a11y.atf.AppiumDriverManager;
import com.poc.a11y.atf.AtfHarnessRunner;
import com.poc.a11y.cloud.CloudPlatformAdapter;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * USB / FireFlink Client Android: Appium launches (or attaches), UiAutomator2
 * is stopped over adb so ATF can take the UiAutomation slot, then a new
 * UiAutomator2 session is restored afterward.
 */
@Component
public class LocalAdbAndroidScanStrategy implements AndroidScanStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalAdbAndroidScanStrategy.class);

    private final AtfHarnessRunner harnessRunner;
    private final AppiumDriverManager appiumDriverManager;
    private final AndroidScanSupport support;

    public LocalAdbAndroidScanStrategy(AtfHarnessRunner harnessRunner,
                                       AppiumDriverManager appiumDriverManager,
                                       AndroidScanSupport support) {
        this.harnessRunner = harnessRunner;
        this.appiumDriverManager = appiumDriverManager;
        this.support = support;
    }

    @Override
    public boolean supports(CloudPlatformAdapter adapter) {
        return adapter.usesAdb();
    }

    @Override
    public ScanResult scan(ScanRequest request, CloudPlatformAdapter adapter) {
        try {
            positionAppThenReleaseUiAutomation(request);
            return support.toScanResult(request, harnessRunner.runScan(request));
        } finally {
            restorePhysicalUiAutomatorThenCloseApp(request);
        }
    }

    private void positionAppThenReleaseUiAutomation(ScanRequest request) {
        AndroidDriver driver = acquirePhysicalDriver(request);
        support.enrichPackageFromSession(request, driver);
        appiumDriverManager.stopUiAutomatorServer(request.getDeviceName());
    }

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
        support.confirmLaunch(request, driver);
        return driver;
    }

    private void restorePhysicalUiAutomatorThenCloseApp(ScanRequest request) {
        AndroidDriver restored = null;
        try {
            restored = appiumDriverManager.startUiAutomatorServer(request);
            if (request.isCloseApp()) {
                appiumDriverManager.terminateApp(restored, request.getAppPackage());
                appiumDriverManager.closeAppiumSession(restored);
            }
        } catch (Exception e) {
            log.warn("Could not start UiAutomator2 after ATF: {}", e.getMessage());
            if (request.isCloseApp() && restored != null) {
                appiumDriverManager.closeAppiumSession(restored);
            }
        }
    }
}
