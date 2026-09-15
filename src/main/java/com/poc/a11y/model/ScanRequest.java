package com.poc.a11y.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScanRequest {

    private String appName;
    /** Absolute path to the app on the machine running Appium (.apk / .app / .ipa), or omit if already installed. */
    private String appPath;

    @NotBlank
    private String platformName = "ANDROID";

    private String automationName = "UiAutomator2";

    private String deviceName = "RZ8TA1A27YM";

    /** Use instead of appPath if the Android app is already installed. */
    private String appPackage;
    private String appActivity;

    /** iOS bundle id. Falls back to {@link #appPackage} when omitted. */
    private String bundleId;

    private String appiumServerUrl = "http://127.0.0.1:4723";

    /** Seconds to wait after launch before capturing the accessibility tree. */
    private int postLaunchWaitSeconds = 3;

    /** Minimum touch target size in dp; elements below this on both axes are flagged. */
    private int minTouchTargetDp = 48;

    /** Device screen density (px per dp), used to convert the dp threshold above into px. */
    private double densityScale = 2.75; // ~xxhdpi default, override per-device

    /**
     * In-process only (not sent over HTTP). When the real scanner already has
     * a live Appium driver, pass it here so this POC does not launch the app
     * again. Android: the session is quit before ATF (to free UiAutomation)
     * but the app process is left running. iOS: the XCUITest session is kept
     * for {@code mobile: performAccessibilityAudit}.
     */
    @JsonIgnore
    private AppiumDriver existingDriver;

    /**
     * HTTP equivalent of an existing Appium session. When set, the service
     * attaches instead of starting a new session / launching the app.
     */
    private String appiumSessionId;

    /**
     * When true, scroll the screen and scan each viewport. When false/omitted,
     * only the current viewport is scanned. Payload key: {@code "scroll"}
     * (alias {@code "scrollToEnd"} still accepted).
     */
    @JsonProperty("scroll")
    @JsonAlias("scrollToEnd")
    private boolean scroll = false;

    private int maxScrolls = 5;

    /** When true, force-stop the app after the ATF scan. Default leaves it open. */
    private boolean closeApp = false;

    /**
     * When true, use a Sauce Labs Android emulator: install the APK at
     * {@link #appPath}, launch it via Appium, then run the ATF harness.
     * Physical USB devices leave this false / omitted.
     */
    private boolean virtualDevice = false;

    private String sauceUsername;
    private String sauceAccessKey;
    /** Sauce data-center region, e.g. {@code us-west-1}, {@code eu-central-1}, {@code us-east-4}. */
    private String sauceRegion = "eu-central-1";
    /** Required for Sauce virtual devices (emulator OS version), e.g. {@code 14.0}. */
    private String platformVersion;

    public boolean hasExistingDriver() {
        return existingDriver != null
                || (appiumSessionId != null && !appiumSessionId.isBlank());
    }

    public boolean isIos() {
        return platformName != null && "ios".equalsIgnoreCase(platformName.trim());
    }

    /** iOS bundle id, or {@link #appPackage} when bundleId was not set. */
    public String resolveBundleId() {
        if (bundleId != null && !bundleId.isBlank()) {
            return bundleId.trim();
        }
        return blankToNull(appPackage);
    }

    /**
     * Automation engine for the current platform. Defaults to XCUITest on iOS
     * even when the request still has the Android default {@code UiAutomator2}.
     */
    public String resolveAutomationName() {
        if (isIos()) {
            if (automationName == null || automationName.isBlank()
                    || "UiAutomator2".equalsIgnoreCase(automationName)) {
                return "XCUITest";
            }
            return automationName;
        }
        if (automationName == null || automationName.isBlank()) {
            return "UiAutomator2";
        }
        return automationName;
    }

    public AndroidDriver getExistingAndroidDriver() {
        return existingDriver instanceof AndroidDriver androidDriver ? androidDriver : null;
    }

    public IOSDriver getExistingIosDriver() {
        return existingDriver instanceof IOSDriver iosDriver ? iosDriver : null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public String getAppName() {return appName;}
    public void setAppName(String appName) {this.appName = appName;}

    public String getAppPath() { return appPath; }
    public void setAppPath(String appPath) { this.appPath = appPath; }

    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }

    public String getAutomationName() { return automationName; }
    public void setAutomationName(String automationName) { this.automationName = automationName; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }

    public String getAppActivity() { return appActivity; }
    public void setAppActivity(String appActivity) { this.appActivity = appActivity; }

    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }

    public String getAppiumServerUrl() { return appiumServerUrl; }
    public void setAppiumServerUrl(String appiumServerUrl) { this.appiumServerUrl = appiumServerUrl; }

    public int getPostLaunchWaitSeconds() { return postLaunchWaitSeconds; }
    public void setPostLaunchWaitSeconds(int postLaunchWaitSeconds) { this.postLaunchWaitSeconds = postLaunchWaitSeconds; }

    public int getMinTouchTargetDp() { return minTouchTargetDp; }
    public void setMinTouchTargetDp(int minTouchTargetDp) { this.minTouchTargetDp = minTouchTargetDp; }

    public double getDensityScale() { return densityScale; }
    public void setDensityScale(double densityScale) { this.densityScale = densityScale; }

    public AppiumDriver getExistingDriver() { return existingDriver; }
    public void setExistingDriver(AppiumDriver existingDriver) { this.existingDriver = existingDriver; }

    public String getAppiumSessionId() { return appiumSessionId; }
    public void setAppiumSessionId(String appiumSessionId) { this.appiumSessionId = appiumSessionId; }

    public boolean isScroll() { return scroll; }
    public void setScroll(boolean scroll) { this.scroll = scroll; }

    /** @deprecated Prefer {@link #isScroll()}; kept for callers using the old name. */
    public boolean isScrollToEnd() { return scroll; }

    /** @deprecated Prefer {@link #setScroll(boolean)}; kept for callers using the old name. */
    public void setScrollToEnd(boolean scrollToEnd) { this.scroll = scrollToEnd; }

    public int getMaxScrolls() { return maxScrolls; }
    public void setMaxScrolls(int maxScrolls) { this.maxScrolls = maxScrolls; }

    public boolean isCloseApp() { return closeApp; }
    public void setCloseApp(boolean closeApp) { this.closeApp = closeApp; }

    public boolean isVirtualDevice() { return virtualDevice; }
    public void setVirtualDevice(boolean virtualDevice) { this.virtualDevice = virtualDevice; }

    public String getSauceUsername() { return sauceUsername; }
    public void setSauceUsername(String sauceUsername) { this.sauceUsername = sauceUsername; }

    public String getSauceAccessKey() { return sauceAccessKey; }
    public void setSauceAccessKey(String sauceAccessKey) { this.sauceAccessKey = sauceAccessKey; }

    public String getSauceRegion() { return sauceRegion; }
    public void setSauceRegion(String sauceRegion) { this.sauceRegion = sauceRegion; }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }
}
