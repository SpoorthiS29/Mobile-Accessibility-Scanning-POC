package com.poc.a11y.ios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.a11y.atf.AppiumDriverManager;
import com.poc.a11y.ios.dto.IosAuditRawOutput;
import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.ios.IOSDriver;
import org.openqa.selenium.OutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs Apple's XCUIAccessibilityAudit through Appium XCUITest
 * ({@code mobile: performAccessibilityAudit}), optionally scrolling
 * viewports, and writes raw JSON + screenshots under {@code ios-results/}.
 */
@Component
public class IosAccessibilityAuditor {

    private static final Logger log = LoggerFactory.getLogger(IosAccessibilityAuditor.class);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper objectMapper;

    public IosAccessibilityAuditor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IosAuditRawOutput run(IOSDriver driver, ScanRequest request) {
        String appName = safeAppName(request.getAppName());
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path resultsDir = Paths.get("ios-results");
        Path shotsDir = resultsDir.resolve("ios-scan-" + appName + "-" + timestamp + "-shots");
        try {
            Files.createDirectories(shotsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create ios-results directory", e);
        }

        List<Map<String, Object>> viewports = new ArrayList<>();
        String previousSourceHash = null;
        int unchanged = 0;
        int maxScrolls = Math.max(0, request.getMaxScrolls());
        int maxPasses = request.isScroll() ? maxScrolls + 1 : 1;

        for (int pass = 0; pass < maxPasses; pass++) {
            if (pass == 0) {
                AppiumDriverManager.sleepQuietly(Math.max(0, request.getPostLaunchWaitSeconds()));
            } else {
                AppiumDriverManager.sleepQuietly(1);
            }

            List<Map<String, Object>> issues = performAudit(driver);
            Path shot = shotsDir.resolve(String.format("viewport-%03d.png", pass));
            captureScreenshot(driver, shot);

            Map<String, Object> viewport = new LinkedHashMap<>();
            viewport.put("index", pass);
            viewport.put("screenshotFile", shot.toAbsolutePath().toString());
            viewport.put("issues", issues);
            viewports.add(viewport);

            log.info("iOS viewport {}: {} accessibility issue(s), screenshot={}",
                    pass, issues.size(), shot.getFileName());

            if (!request.isScroll() || pass == maxPasses - 1) {
                break;
            }

            String hash = pageSourceHash(driver);
            swipeUp(driver);
            AppiumDriverManager.sleepQuietly(1);
            String next = pageSourceHash(driver);
            if (next.equals(hash) || next.equals(previousSourceHash)) {
                unchanged++;
                log.info("Scroll produced no new content (streak={})", unchanged);
                if (unchanged >= 2) {
                    break;
                }
            } else {
                unchanged = 0;
            }
            previousSourceHash = hash;
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("platform", "iOS");
        raw.put("bundleId", request.resolveBundleId());
        raw.put("deviceName", request.getDeviceName());
        raw.put("viewportCount", viewports.size());
        raw.put("viewports", viewports);

        Path jsonFile = resultsDir.resolve("ios-scan-" + appName + "-" + timestamp + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), raw);
            log.info("iOS accessibility results saved to: {}", jsonFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save iOS accessibility results", e);
        }

        IosAuditRawOutput output = new IosAuditRawOutput();
        output.setRawJsonPath(jsonFile.toAbsolutePath().toString());
        output.setViewports(viewports);
        return output;
    }

    private List<Map<String, Object>> performAudit(IOSDriver driver) {
        Object raw;
        try {
            raw = driver.executeScript(
                    "mobile: performAccessibilityAudit",
                    Map.of("auditTypes", List.of("XCUIAccessibilityAuditTypeAll")));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "mobile: performAccessibilityAudit failed. Requires Xcode 15 / iOS 17+ and XCUITest. "
                            + e.getMessage(),
                    e);
        }
        return normalizeIssues(raw);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeIssues(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> issues = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    issues.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return issues;
        }
        log.warn("Unexpected accessibility audit result type: {}", raw.getClass().getName());
        return List.of();
    }

    private void captureScreenshot(IOSDriver driver, Path shot) {
        try {
            Files.write(shot, driver.getScreenshotAs(OutputType.BYTES));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write screenshot " + shot, e);
        }
    }

    private void swipeUp(IOSDriver driver) {
        try {
            driver.executeScript("mobile: swipe", Map.of(
                    "direction", "up",
                    "percent", 0.75
            ));
        } catch (Exception e) {
            log.warn("mobile: swipe failed ({}), trying mobile: scroll", e.getMessage());
            driver.executeScript("mobile: scroll", Map.of("direction", "down"));
        }
    }

    private static String pageSourceHash(IOSDriver driver) {
        try {
            String source = driver.getPageSource();
            return Integer.toHexString(source == null ? 0 : source.hashCode());
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            return "app";
        }
        return appName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
