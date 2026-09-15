package com.poc.a11y.ios;

import com.poc.a11y.atf.AtfProperties;
import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.Capabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * Starts or attaches an Appium XCUITest session. Unlike Android ATF, iOS
 * accessibility audit runs inside this session and must not quit WDA first.
 */
@Component
public class IosDriverManager {

    private static final Logger log = LoggerFactory.getLogger(IosDriverManager.class);

    private final AtfProperties properties;

    public IosDriverManager(AtfProperties properties) {
        this.properties = properties;
    }

    public IOSDriver start(ScanRequest request) {
        String bundleId = request.resolveBundleId();
        String appPath = blankToNull(request.getAppPath());
        if (bundleId == null && appPath == null) {
            throw new IllegalArgumentException(
                    "iOS scan requires existingDriver / appiumSessionId, or bundleId (or appPackage) / appPath");
        }

        XCUITestOptions options = baseOptions(request);
        options.setNoReset(true);
        options.setCapability("appium:autoLaunch", true);
        options.setCapability("appium:shouldTerminateApp", false);

        if (appPath != null) {
            options.setApp(appPath);
        }
        if (bundleId != null) {
            options.setBundleId(bundleId);
        }

        log.info("Starting XCUITest session bundleId={} appPath={} device={}",
                bundleId, appPath, request.getDeviceName());

        IOSDriver driver = new IOSDriver(serverUrl(request), options);
        activateApp(driver, bundleId);
        return driver;
    }

    public IOSDriver attach(ScanRequest request) {
        String sessionId = request.getAppiumSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("appiumSessionId is required to attach");
        }
        XCUITestOptions options = baseOptions(request);
        options.setNoReset(true);
        options.setCapability("appium:autoLaunch", false);

        log.info("Attaching to existing XCUITest session {}", sessionId);
        return new IOSDriver(serverUrl(request), options) {
            @Override
            protected void startSession(Capabilities capabilities) {
                setSessionId(sessionId.trim());
            }
        };
    }

    public void terminateApp(IOSDriver driver, String bundleId) {
        if (driver == null) {
            return;
        }
        if (bundleId == null || bundleId.isBlank()) {
            log.warn("closeApp=true but bundleId is empty — cannot terminate");
            return;
        }
        try {
            log.info("closeApp=true — terminating {}", bundleId);
            driver.terminateApp(bundleId.trim());
        } catch (Exception e) {
            log.warn("Could not terminate app: {}", e.getMessage());
        }
    }

    public void closeSession(IOSDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            log.info("Closing Appium XCUITest session");
            driver.quit();
        } catch (Exception e) {
            log.warn("Could not close Appium session: {}", e.getMessage());
        }
    }

    private void activateApp(IOSDriver driver, String bundleId) {
        if (bundleId == null) {
            return;
        }
        try {
            log.info("Activating app {}", bundleId);
            driver.activateApp(bundleId);
        } catch (Exception e) {
            log.warn("activateApp failed: {}", e.getMessage());
        }
    }

    private XCUITestOptions baseOptions(ScanRequest request) {
        XCUITestOptions options = new XCUITestOptions()
                .setAutomationName(request.resolveAutomationName())
                .setPlatformName("iOS")
                .setDeviceName(request.getDeviceName())
                .setNewCommandTimeout(Duration.ofSeconds(180))
                .setWdaLaunchTimeout(Duration.ofSeconds(120));
        if (request.getDeviceName() != null && !request.getDeviceName().isBlank()) {
            options.setUdid(request.getDeviceName());
        }
        if (request.getPlatformVersion() != null && !request.getPlatformVersion().isBlank()) {
            options.setPlatformVersion(request.getPlatformVersion());
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
