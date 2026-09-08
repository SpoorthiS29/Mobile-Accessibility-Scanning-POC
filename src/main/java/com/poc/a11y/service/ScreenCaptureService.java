package com.poc.a11y.service;

import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.OutputType;
import org.springframework.stereotype.Component;

@Component
public class ScreenCaptureService {

    /** Raw UiAutomator2 accessibility-tree XML for whatever screen is currently on top. */
    public String capturePageSource(AndroidDriver driver) {
        return driver.getPageSource();
    }

    /**
     * Screenshot as PNG bytes. Not consumed by the current rule set, but wired up
     * so a future contrast-ratio check (pixel sampling against text bounds) can
     * reuse this instead of adding a second Appium round trip.
     */
    public byte[] captureScreenshot(AndroidDriver driver) {
        return driver.getScreenshotAs(OutputType.BYTES);
    }
}
