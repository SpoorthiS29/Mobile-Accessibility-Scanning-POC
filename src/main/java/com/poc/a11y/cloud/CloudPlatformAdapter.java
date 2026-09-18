package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.openqa.selenium.MutableCapabilities;

import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

/**
 * One adapter per cloud vendor (plus local USB / FireFlink Client).
 * Session URL, vendor options, and app upload live here so Android and iOS
 * driver managers stay vendor-agnostic. A new provider is a new class;
 * scan strategies only split when the device-control flow itself differs
 * (local ADB vs Appium {@code mobile: shell}).
 */
public interface CloudPlatformAdapter {

    CloudPlatform platform();

    URL appiumHubUrl(ScanRequest request);

    /**
     * W3C vendor blob: {@code sauce:options}, {@code bstack:options},
     * {@code lt:options}. Empty for local.
     */
    Map<String, Object> vendorOptions(ScanRequest request);

    String vendorOptionsKey();

    /**
     * Upload a local APK/IPA (or harness APK) and return the Appium {@code app}
     * capability value. Local returns an absolute filesystem path.
     */
    String uploadApp(ScanRequest request, Path localFile);

    /**
     * Resolves {@code appPath} to an Appium {@code app} value: remote ref as-is,
     * local file uploaded, or {@code null} when launching by package/bundle id.
     */
    String resolveAppCapability(ScanRequest request);

    void applyVendorOptions(MutableCapabilities capabilities, ScanRequest request);

    void validate(ScanRequest request);

    default boolean isLocal() {
        return platform().isLocal();
    }

    /**
     * Local USB Android uses adb. Cloud sessions must not.
     */
    default boolean usesAdb() {
        return isLocal();
    }
}
