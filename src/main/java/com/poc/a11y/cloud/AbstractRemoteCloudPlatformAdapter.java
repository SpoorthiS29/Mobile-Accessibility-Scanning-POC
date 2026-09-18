package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.openqa.selenium.MutableCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared cloud-session rules: credentials, device identity, and app upload.
 * Provider-specific hub URLs, vendor options, and storage APIs stay in subclasses.
 */
public abstract class AbstractRemoteCloudPlatformAdapter implements CloudPlatformAdapter {

    @Override
    public final void applyVendorOptions(MutableCapabilities capabilities, ScanRequest request) {
        Map<String, Object> vendor = vendorOptions(request);
        if (vendor != null && !vendor.isEmpty()) {
            capabilities.setCapability(vendorOptionsKey(), vendor);
        }
    }

    @Override
    public void validate(ScanRequest request) {
        if (isBlank(request.resolveCloudUsername()) || isBlank(request.resolveCloudAccessKey())) {
            throw new IllegalArgumentException(
                    platform() + " requires cloudUsername and cloudAccessKey");
        }
        if (isBlank(request.getDeviceName())) {
            throw new IllegalArgumentException(platform() + " requires deviceName");
        }
        if (isBlank(request.getPlatformVersion())) {
            throw new IllegalArgumentException(
                    platform() + " requires platformVersion (e.g. \"14.0\" or \"18.0\")");
        }
        if (!hasLaunchableApp(request)) {
            throw new IllegalArgumentException(
                    platform() + " requires appPath (local file or storage:/bs://lt:// reference), "
                            + "or an already-installed appPackage / bundleId");
        }
    }

    @Override
    public String resolveAppCapability(ScanRequest request) {
        String appPath = trimToNull(request.getAppPath());
        if (appPath == null) {
            return null;
        }
        if (CloudAppRefs.isRemoteRef(appPath)) {
            return appPath;
        }
        Path file = Path.of(appPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("appPath does not exist: " + file);
        }
        return uploadApp(request, file);
    }

    protected static boolean hasLaunchableApp(ScanRequest request) {
        String appPath = trimToNull(request.getAppPath());
        if (appPath != null) {
            return true;
        }
        return trimToNull(request.getAppPackage()) != null
                || trimToNull(request.getBundleId()) != null;
    }

    protected static String sessionName(ScanRequest request) {
        if (request.getAppName() == null || request.getAppName().isBlank()) {
            return "accessibility scan";
        }
        return "a11y scan: " + request.getAppName().trim();
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
