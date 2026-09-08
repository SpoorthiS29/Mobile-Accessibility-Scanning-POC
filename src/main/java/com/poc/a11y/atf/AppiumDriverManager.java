package com.poc.a11y.atf;

import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.Capabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;

/**
 * Starts a new Appium session (POC: launches the app) or attaches to an
 * existing one. {@link #releaseUiAutomation} quits the session so ATF can
 * take the device's single UiAutomation slot, without force-stopping the app.
 */
@Component
public class AppiumDriverManager {

    private static final Logger log = LoggerFactory.getLogger(AppiumDriverManager.class);

    private final AtfProperties properties;

    public AppiumDriverManager(AtfProperties properties) {
        this.properties = properties;
    }

    public AndroidDriver start(ScanRequest request) {
        String appPackage = blankToNull(request.getAppPackage());
        String appActivity = blankToNull(request.getAppActivity());
        String appPath = blankToNull(request.getAppPath());

        if (appPath == null && appPackage == null) {
            throw new IllegalArgumentException(
                    "ScanRequest must supply existingDriver / appiumSessionId, or appPath / appPackage");
        }

        UiAutomator2Options options = baseOptions(request);
        options.setNoReset(true);
        options.setCapability("appium:dontStopAppOnReset", true);
        options.setCapability("appium:autoLaunch", true);
        options.setCapability("appium:forceAppLaunch", true);

        if (appPath != null) {
            options.setApp(appPath);
        }
        if (appPackage != null) {
            options.setAppPackage(appPackage);
            options.setAppWaitPackage(appPackage);
        }
        if (appActivity != null) {
            options.setAppActivity(appActivity);
            options.setAppWaitActivity("*");
        }

        log.info("Starting Appium session to launch app package={} activity={} appPath={}",
                appPackage, appActivity, appPath);

        AndroidDriver driver = new AndroidDriver(serverUrl(request), options);
        bringAppToForeground(driver, request);
        return driver;
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
        options.setCapability("appium:dontStopAppOnReset", true);
        options.setCapability("appium:autoLaunch", false);

        return new AndroidDriver(url, options) {
            @Override
            protected void startSession(Capabilities capabilities) {
                setSessionId(sessionId.trim());
            }
        };
    }

    /**
     * Drops the UiAutomator2 instrumentation so ATF can run. Does not
     * {@code terminateApp} / {@code force-stop}.
     */
    public void releaseUiAutomation(AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception ignored) {
            // session may already be gone
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
}
