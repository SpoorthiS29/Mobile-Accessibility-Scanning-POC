package com.poc.a11y.ios;

import com.poc.a11y.atf.dto.AtfElementOutputDto;
import com.poc.a11y.atf.dto.WcagMapping;
import com.poc.a11y.ios.dto.IosAuditRawOutput;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.Severity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Maps raw XCUIAccessibilityAudit items onto the same {@link Issue} /
 * {@link com.poc.a11y.model.ScanResult} shape used by the Android ATF path.
 * Screenshots are encoded as base64 for the HTTP response.
 */
@Component
public class IosAuditResultMapper {

    private static final Map<String, WcagMapping> WCAG_BY_AUDIT = Map.ofEntries(
            Map.entry("XCUIAccessibilityAuditTypeContrast",
                    new WcagMapping("1.4.3 Contrast (Minimum)", "AA")),
            Map.entry("XCUIAccessibilityAuditTypeHitRegion",
                    new WcagMapping("2.5.5 Target Size", "AAA")),
            Map.entry("XCUIAccessibilityAuditTypeSufficientElementDescription",
                    new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("XCUIAccessibilityAuditTypeElementDetection",
                    new WcagMapping("1.3.1 Info and Relationships", "A")),
            Map.entry("XCUIAccessibilityAuditTypeDynamicType",
                    new WcagMapping("1.4.4 Resize Text", "AA")),
            Map.entry("XCUIAccessibilityAuditTypeTextClipped",
                    new WcagMapping("1.4.4 Resize Text", "AA")),
            Map.entry("XCUIAccessibilityAuditTypeTraits",
                    new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("XCUIAccessibilityAuditTypeElementInParent",
                    new WcagMapping("1.3.1 Info and Relationships", "A"))
    );

    public List<Issue> toIssues(IosAuditRawOutput raw) {
        if (raw == null || raw.getViewports() == null) {
            return List.of();
        }
        List<Issue> issues = new ArrayList<>();
        for (Map<String, Object> viewport : raw.getViewports()) {
            String screenshotPath = str(viewport.get("screenshotFile"));
            String base64 = toBase64(screenshotPath);
            Object batchObj = viewport.get("issues");
            if (!(batchObj instanceof List<?> batch)) {
                continue;
            }
            for (Object item : batch) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> issueMap = (Map<String, Object>) map;
                    issues.add(toIssue(issueMap, screenshotPath, base64));
                }
            }
        }
        return issues;
    }

    private Issue toIssue(Map<String, Object> raw, String screenshotPath, String base64) {
        String auditType = str(raw.get("auditType"));
        if (auditType == null) {
            auditType = "Unknown";
        }
        String compact = str(raw.get("compactDescription"));
        if (compact == null) {
            compact = auditType;
        }
        String detailed = str(raw.get("detailedDescription"));
        if (detailed == null) {
            detailed = compact;
        }

        WcagMapping wcag = WCAG_BY_AUDIT.get(auditType);

        Issue issue = new Issue(
                auditType,
                compact,
                detailed,
                Severity.SERIOUS,
                null,
                wcag != null ? wcag.getCriterion() : null
        );
        issue.setCheck(auditType);
        issue.setType("ERROR");
        issue.setConformanceLevel(wcag != null ? wcag.getConformanceLevel() : null);
        issue.setScreenshotPath(screenshotPath);
        issue.setScreenshot(base64);
        issue.setElement(toElement(raw.get("elementAttributes")));
        return issue;
    }

    private AtfElementOutputDto toElement(Object attrsObj) {
        if (!(attrsObj instanceof Map<?, ?> attrs)) {
            return null;
        }
        AtfElementOutputDto element = new AtfElementOutputDto();
        element.setClassName(str(attrs.get("type")));
        element.setResourceId(str(attrs.get("name")));
        element.setContentDescription(str(attrs.get("label")));
        element.setText(str(attrs.get("value")));
        return element;
    }

    private String toBase64(String screenshotPath) {
        if (screenshotPath == null || screenshotPath.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(screenshotPath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return null;
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }
}
