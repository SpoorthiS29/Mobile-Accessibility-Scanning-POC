package com.poc.a11y.service;

import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * Wraps creation/teardown of an AndroidDriver session. Kept isolated so the
 * scan engine doesn't need to know Appium capability wiring.
 */
@Component
public class AppiumSessionManager {

    public AndroidDriver start(ScanRequest request) {
        UiAutomator2Options options = new UiAutomator2Options()
                .setAutomationName(request.getAutomationName())
                .setPlatformName(request.getPlatformName())
                .setDeviceName(request.getDeviceName())
                .setNewCommandTimeout(Duration.ofSeconds(120));

        if (request.getAppPath() != null && !request.getAppPath().isBlank()) {
            options.setApp(request.getAppPath());
        } else if (request.getAppPackage() != null && !request.getAppPackage().isBlank()) {
            options.setAppPackage(request.getAppPackage());
            if (request.getAppActivity() != null && !request.getAppActivity().isBlank()) {
                options.setAppActivity(request.getAppActivity());
            }
        } else {
            throw new IllegalArgumentException("ScanRequest must supply either appPath or appPackage");
        }

        try {
            URL serverUrl = new URL(request.getAppiumServerUrl());
            return new AndroidDriver(serverUrl, options);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid appiumServerUrl: " + request.getAppiumServerUrl(), e);
        }
    }

    public void stop(AndroidDriver driver) {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignored) {
                // best-effort cleanup for a POC; log properly in a real service
            }
        }
    }
}
