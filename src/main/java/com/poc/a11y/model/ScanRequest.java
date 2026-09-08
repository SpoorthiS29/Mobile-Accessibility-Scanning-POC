package com.poc.a11y.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.appium.java_client.android.AndroidDriver;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ScanRequest {

    private String appName;
    /** Absolute path to the .apk on the machine running Appium, OR an already-installed app package. */
    private String appPath;

    @NotBlank
    private String platformName = "Android";

    private String automationName = "UiAutomator2";

    private String deviceName = "RZ8TA1A27YM";

    /** Use instead of appPath if the app is already installed. */
    private String appPackage;
    private String appActivity;

    private String appiumServerUrl = "http://127.0.0.1:4723";

    /** Seconds to wait after launch before capturing the accessibility tree. */
    private int postLaunchWaitSeconds = 3;

    /** Minimum touch target size in dp; elements below this on both axes are flagged. */
    private int minTouchTargetDp = 48;

    /** Device screen density (px per dp), used to convert the dp threshold above into px. */
    private double densityScale = 2.75; // ~xxhdpi default, override per-device

    /**
     * In-process only (not sent over HTTP). When the real scanner already has
     * a live {@link AndroidDriver}, pass it here so this POC does not launch
     * the app again. The session is quit before ATF (to free UiAutomation)
     * but the app process is left running.
     */
    @JsonIgnore
    private AndroidDriver existingDriver;

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

    private int maxScrolls = 12;

    /** When true, force-stop the app after the ATF scan. Default leaves it open. */
    private boolean closeApp = false;

    public boolean hasExistingDriver() {
        return existingDriver != null
                || (appiumSessionId != null && !appiumSessionId.isBlank());
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

    public String getAppiumServerUrl() { return appiumServerUrl; }
    public void setAppiumServerUrl(String appiumServerUrl) { this.appiumServerUrl = appiumServerUrl; }

    public int getPostLaunchWaitSeconds() { return postLaunchWaitSeconds; }
    public void setPostLaunchWaitSeconds(int postLaunchWaitSeconds) { this.postLaunchWaitSeconds = postLaunchWaitSeconds; }

    public int getMinTouchTargetDp() { return minTouchTargetDp; }
    public void setMinTouchTargetDp(int minTouchTargetDp) { this.minTouchTargetDp = minTouchTargetDp; }

    public double getDensityScale() { return densityScale; }
    public void setDensityScale(double densityScale) { this.densityScale = densityScale; }

    public AndroidDriver getExistingDriver() { return existingDriver; }
    public void setExistingDriver(AndroidDriver existingDriver) { this.existingDriver = existingDriver; }

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
}
