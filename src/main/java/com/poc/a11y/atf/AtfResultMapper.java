package com.poc.a11y.atf;

import com.poc.a11y.atf.dto.*;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.Severity;
import com.poc.a11y.model.UiElement;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts raw real-ATF results (as parsed from atf-harness's JSON dump)
 * into this project's existing {@link Issue}/{@link UiElement} model, so
 * downstream consumers (JSON response shape, any future persistence layer)
 * don't need to know whether a scan used the hand-rolled checks in
 * {@code com.poc.a11y.checks} or the real ATF harness.
 */
@Component
public class AtfResultMapper {

    /**
     * Best-effort WCAG mapping per real ATF check class. Not exhaustive -
     * the {@code AccessibilityCheckPreset.LATEST} preset has ~30 checks;
     * extend this as you encounter more in your findings. Unmapped checks
     * simply surface with {@code wcagCriterion = null}.
     */
    private static final Map<String, WcagMapping> WCAG_BY_CHECK = Map.ofEntries(
            Map.entry("TouchTargetSizeCheck", new WcagMapping("2.5.5 Target Size", "AAA")),
            Map.entry("DuplicateClickableBoundsCheck", new WcagMapping("2.5.5 Target Size", "AAA")),
            Map.entry("TextContrastCheck", new WcagMapping("1.4.3 Contrast (Minimum)", "AA")),
            Map.entry("ImageContrastCheck", new WcagMapping("1.4.11 Non-text Contrast", "AA")),
            Map.entry("SpeakableTextPresentCheck", new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("DuplicateSpeakableTextCheck", new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("EditableContentDescCheck", new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("RedundantDescriptionCheck", new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("ClassNameCheck", new WcagMapping("4.1.2 Name, Role, Value", "A")),
            Map.entry("ClickableSpanCheck", new WcagMapping("2.5.5 Target Size", "AAA")),
            Map.entry("TraversalOrderCheck", new WcagMapping("1.3.2 Meaningful Sequence", "A")),
            Map.entry("LinkPurposeUnclearCheck", new WcagMapping("2.4.4 Link Purpose (In Context)", "A")),
            Map.entry("ItemsShouldNotAutoFocusCheck", new WcagMapping("3.2.1 On Focus", "A")),
            Map.entry("SwitchAccessActionsCheck", new WcagMapping("2.1.1 Keyboard", "A")),
            Map.entry("SwitchAccessScrollableViewCheck", new WcagMapping("2.1.1 Keyboard", "A"))
    );

    public List<Issue> toIssues(AtfScanOutputDto output) {
        if (output == null || output.getResults() == null) {
            return List.of();
        }
        List<Issue> issues = new ArrayList<>(output.getResults().size());
        for (AtfCheckResultDto raw : output.getResults()) {
            issues.add(toIssue(raw));
        }
        return issues;
    }

    private Issue toIssue(AtfCheckResultDto raw) {

        String check = raw.getCheckClass();
        String type = normalizeAtfType(raw.getType());

        Severity severity = toSeverity(type);

        WcagMapping wcagMapping = WCAG_BY_CHECK.get(check);

        String wcag = wcagMapping != null
                ? wcagMapping.getCriterion()
                : null;

        String conformanceLevel = wcagMapping != null
                ? wcagMapping.getConformanceLevel()
                : null;

        Issue issue = new Issue(
                "ATF_" + check,
                check,
                raw.getMessage(),
                severity,
                null,
                wcag
        );

        issue.setCheck(check);
        issue.setType(type);
        issue.setDescription(raw.getMessage());

        issue.setWcagCriterion(wcag);
        issue.setConformanceLevel(conformanceLevel);

        String screenshotPath = raw.getScreenshotFile();

        issue.setScreenshotPath(screenshotPath);
        issue.setScreenshot(toBase64DataUri(screenshotPath));

        issue.setElement(toOutputElement(raw.getElement()));

        return issue;
    }

    private AtfElementOutputDto toOutputElement(AtfElementDto dto) {

        if (dto == null) {
            return null;
        }

        AtfElementOutputDto element = new AtfElementOutputDto();

        element.setClassName(dto.getClassName());
        element.setResourceId(dto.getResourceName());
        element.setContentDescription(dto.getContentDescription());
        element.setText(dto.getText());

//        element.setClickable(dto.isClickable());
//        element.setEnabled(dto.isEnabled());

        return element;
    }

    private String normalizeAtfType(String type) {
        if (type == null) {
            return null;
        }

        return type.toUpperCase();
    }

    /**
     * ATF's own severity model is ERROR/WARNING/INFO (see
     * {@code AccessibilityCheckResult.AccessibilityCheckResultType}), which
     * doesn't line up 1:1 with this project's 4-level Severity. Mapping
     * chosen here: ERROR (clear a11y bug) -> CRITICAL, WARNING (potential
     * bug, needs human judgement) -> MODERATE, INFO -> MINOR. Adjust freely.
     */
    private Severity toSeverity(String atfType) {
        if (atfType == null) {
            return null;
        }

        return switch (atfType.toUpperCase()) {
            case "ERROR" -> Severity.CRITICAL;
            case "WARNING" -> Severity.MODERATE;
            case "INFO" -> Severity.MINOR;

            // These are not actual accessibility severities.
            // They represent ATF execution/result states.
            case "RESOLVED", "NOT_RUN", "SUPPRESSED" -> null;

            default -> null;
        };
    }

    private UiElement toUiElement(AtfElementDto dto) {
        if (dto == null) {
            return null;
        }

        UiElement element = new UiElement();

        element.setClassName(dto.getClassName());
        element.setResourceId(dto.getResourceName());
        element.setContentDesc(dto.getContentDescription());
        element.setText(dto.getText());
        element.setClickable(dto.isClickable());
        element.setEnabled(dto.isEnabled());

        return element;
    }

    private String toBase64DataUri(String screenshotPath) {
        if (screenshotPath == null || screenshotPath.isBlank()) {
            return null;
        }

        try {
            Path path = Paths.get(screenshotPath);

            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return null;
            }

            byte[] imageBytes = Files.readAllBytes(path);

            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String mimeType = Files.probeContentType(path);

            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "image/png";
            }

            return base64;

        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
