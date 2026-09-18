package com.poc.a11y.ios;

import com.poc.a11y.cloud.CloudPlatformAdapter;
import com.poc.a11y.cloud.CloudPlatformRegistry;
import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import org.openqa.selenium.Capabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Duration;

/**
 * Starts or attaches an Appium XCUITest session. Local sessions point Appium
 * at FireFlink Client's signed WDA project. Cloud sessions omit those paths
 * and use the vendor hub + options instead.
 */
@Component
public class IosDriverManager {

    private static final Logger log = LoggerFactory.getLogger(IosDriverManager.class);

    private final IosWdaProperties wdaProperties;
    private final CloudPlatformRegistry cloudPlatformRegistry;

    public IosDriverManager(IosWdaProperties wdaProperties,
                            CloudPlatformRegistry cloudPlatformRegistry) {
        this.wdaProperties = wdaProperties;
        this.cloudPlatformRegistry = cloudPlatformRegistry;
    }

    public IOSDriver start(ScanRequest request) {
        CloudPlatformAdapter adapter = cloudPlatformRegistry.resolve(request);
        adapter.validate(request);

        String bundleId = request.resolveBundleId();
        String appCap = adapter.resolveAppCapability(request);
        if (bundleId == null && appCap == null) {
            throw new IllegalArgumentException(
                    "iOS scan requires existingDriver / appiumSessionId, or bundleId (or appPackage) / appPath");
        }

        XCUITestOptions options = baseOptions(request, adapter);
        options.setNoReset(true);
        options.setCapability("appium:autoLaunch", true);
        options.setCapability("appium:shouldTerminateApp", false);

        if (appCap != null) {
            options.setApp(appCap);
        }
        if (bundleId != null) {
            options.setBundleId(bundleId);
        }

        URL hub = adapter.appiumHubUrl(request);
        log.info("Starting XCUITest session platform={} bundleId={} app={} device={} hub={}",
                adapter.platform(), bundleId, appCap, request.getDeviceName(), hub);

        IOSDriver driver = new IOSDriver(hub, options);
        activateApp(driver, bundleId);
        return driver;
    }

    public IOSDriver attach(ScanRequest request) {
        String sessionId = request.getAppiumSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("appiumSessionId is required to attach");
        }
        CloudPlatformAdapter adapter = cloudPlatformRegistry.resolve(request);
        XCUITestOptions options = baseOptions(request, adapter);
        options.setNoReset(true);
        options.setCapability("appium:autoLaunch", false);

        log.info("Attaching to existing XCUITest session {}", sessionId);
        return new IOSDriver(adapter.appiumHubUrl(request), options) {
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

    private XCUITestOptions baseOptions(ScanRequest request, CloudPlatformAdapter adapter) {
        XCUITestOptions options = new XCUITestOptions()
                .setAutomationName(request.resolveAutomationName())
                .setPlatformName("iOS")
                .setDeviceName(request.getDeviceName())
                .setNewCommandTimeout(Duration.ofSeconds(180))
                .setWdaLaunchTimeout(Duration.ofSeconds(120));
        if (request.getPlatformVersion() != null && !request.getPlatformVersion().isBlank()) {
            options.setPlatformVersion(request.getPlatformVersion());
        }
        if (adapter.isLocal()) {
            applyLocalWda(options, request);
            if (request.getDeviceName() != null && !request.getDeviceName().isBlank()) {
                options.setUdid(request.getDeviceName());
            }
        } else {
            adapter.applyVendorOptions(options, request);
        }
        return options;
    }

    private void applyLocalWda(XCUITestOptions options, ScanRequest request) {
        String agentPath = firstNonBlank(request.getWdaAgentPath(), wdaProperties.getAgentPath());
        String bootstrapPath = firstNonBlank(request.getWdaBootstrapPath(), wdaProperties.getBootstrapPath());
        if (agentPath != null) {
            options.setCapability("appium:agentPath", agentPath);
        }
        if (bootstrapPath != null) {
            options.setCapability("appium:bootstrapPath", bootstrapPath);
        }
        boolean showXcodeLog = request.getShowXcodeLog() != null
                ? request.getShowXcodeLog()
                : wdaProperties.isShowXcodeLog();
        if (showXcodeLog) {
            options.setCapability("appium:showXcodeLog", true);
        }
        String xcodeOrgId = firstNonBlank(request.getXcodeOrgId(), wdaProperties.getXcodeOrgId());
        String xcodeSigningId = firstNonBlank(request.getXcodeSigningId(), wdaProperties.getXcodeSigningId());
        String updatedWdaBundleId = firstNonBlank(
                request.getUpdatedWdaBundleId(), wdaProperties.getUpdatedWdaBundleId());
        if (xcodeOrgId != null) {
            options.setCapability("appium:xcodeOrgId", xcodeOrgId);
        }
        if (xcodeSigningId != null) {
            options.setCapability("appium:xcodeSigningId", xcodeSigningId);
        }
        if (updatedWdaBundleId != null) {
            options.setCapability("appium:updatedWDABundleId", updatedWdaBundleId);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
