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
 * Sauce Labs / BrowserStack / LambdaTest Android: no adb. Appium installs and
 * launches the AUT, then ATF runs over {@code mobile: shell} on that session.
 * Add a separate {@link AndroidScanStrategy} if a vendor cannot support that.
 */
@Component
public class CloudAppiumAndroidScanStrategy implements AndroidScanStrategy {

    private static final Logger log = LoggerFactory.getLogger(CloudAppiumAndroidScanStrategy.class);

    private final AtfHarnessRunner harnessRunner;
    private final AppiumDriverManager appiumDriverManager;
    private final AndroidScanSupport support;

    public CloudAppiumAndroidScanStrategy(AtfHarnessRunner harnessRunner,
                                          AppiumDriverManager appiumDriverManager,
                                          AndroidScanSupport support) {
        this.harnessRunner = harnessRunner;
        this.appiumDriverManager = appiumDriverManager;
        this.support = support;
    }

    @Override
    public boolean supports(CloudPlatformAdapter adapter) {
        return !adapter.usesAdb();
    }

    @Override
    public ScanResult scan(ScanRequest request, CloudPlatformAdapter adapter) {
        AndroidDriver driver = null;
        try {
            log.info("Cloud Android scan on {} — launching app under test", adapter.platform());
            driver = appiumDriverManager.start(request);
            support.confirmLaunch(request, driver);
            return support.toScanResult(request, harnessRunner.runScanOnCloud(request, driver, adapter));
        } finally {
            restoreUiAutomatorThenCloseApp(request, driver);
        }
    }

    private void restoreUiAutomatorThenCloseApp(ScanRequest request, AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        appiumDriverManager.startUiAutomator(driver);
        if (request.isCloseApp()) {
            appiumDriverManager.terminateApp(driver, request.getAppPackage());
            appiumDriverManager.closeAppiumSession(driver);
        }
    }
}
