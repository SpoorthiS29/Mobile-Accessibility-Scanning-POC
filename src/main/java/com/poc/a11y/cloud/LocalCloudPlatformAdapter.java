package com.poc.a11y.cloud;

import com.poc.a11y.atf.AtfProperties;
import com.poc.a11y.model.ScanRequest;
import org.openqa.selenium.MutableCapabilities;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

/**
 * FireFlink Client / local Appium (port 4723). Android uses adb; iOS uses the
 * WDA project bundled in FireFlink Client (applied by {@code IosDriverManager}).
 */
@Component
public class LocalCloudPlatformAdapter implements CloudPlatformAdapter {

    private final AtfProperties properties;

    public LocalCloudPlatformAdapter(AtfProperties properties) {
        this.properties = properties;
    }

    @Override
    public CloudPlatform platform() {
        return CloudPlatform.LOCAL;
    }

    @Override
    public URL appiumHubUrl(ScanRequest request) {
        String raw = request.getAppiumServerUrl();
        if (raw == null || raw.isBlank()) {
            raw = properties.getAppiumServerUrl();
        }
        return CloudHttpSupport.toUrl(raw);
    }

    @Override
    public Map<String, Object> vendorOptions(ScanRequest request) {
        return Map.of();
    }

    @Override
    public String vendorOptionsKey() {
        return "";
    }

    @Override
    public String uploadApp(ScanRequest request, Path localFile) {
        return localFile.toAbsolutePath().normalize().toString();
    }

    @Override
    public String resolveAppCapability(ScanRequest request) {
        String appPath = request.getAppPath();
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        if (CloudAppRefs.isRemoteRef(appPath)) {
            return appPath.trim();
        }
        return Path.of(appPath).toAbsolutePath().normalize().toString();
    }

    @Override
    public void applyVendorOptions(MutableCapabilities capabilities, ScanRequest request) {
        // local Appium has no vendor options blob
    }

    @Override
    public void validate(ScanRequest request) {
        // Local launch validation stays in the driver managers (package / bundle / path).
    }
}
