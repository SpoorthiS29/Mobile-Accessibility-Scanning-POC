package com.poc.a11y.service;

import com.poc.a11y.checks.AccessibilityCheck;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import com.poc.a11y.model.UiElement;
import com.poc.a11y.util.PageSourceParser;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MobileScanEngine {

    private static final Logger log = LoggerFactory.getLogger(MobileScanEngine.class);

    private final AppiumSessionManager sessionManager;
    private final ScreenCaptureService captureService;
    private final PageSourceParser parser;
    private final List<AccessibilityCheck> checks;

    public MobileScanEngine(AppiumSessionManager sessionManager,
                             ScreenCaptureService captureService,
                             PageSourceParser parser,
                             List<AccessibilityCheck> checks) {
        this.sessionManager = sessionManager;
        this.captureService = captureService;
        this.parser = parser;
        this.checks = checks;
        log.info("Loaded {} accessibility checks: {}", checks.size(),
                checks.stream().map(AccessibilityCheck::getId).toList());
    }

    /**
     * Launches the app (or attaches to the given package/activity), waits for it
     * to settle, captures the current screen's accessibility tree, and runs every
     * registered check against it. The Appium session is always torn down,
     * success or failure.
     */
    public ScanResult scanCurrentScreen(ScanRequest request) {
        AndroidDriver driver = sessionManager.start(request);
        try {
            sleepQuietly(request.getPostLaunchWaitSeconds());

            String xml = captureService.capturePageSource(driver);
            List<UiElement> elements = parser.parse(xml);

            List<Issue> issues = new ArrayList<>();
            for (AccessibilityCheck check : checks) {
                issues.addAll(check.evaluate(elements, request));
            }

            return new ScanResult(request.getPlatformName(), request.getDeviceName(),
                    elements.size(), issues);
        } finally {
            sessionManager.stop(driver);
        }
    }

    private void sleepQuietly(int seconds) {
        if (seconds <= 0) return;
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
