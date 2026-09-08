package com.poc.a11y.atf.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Mirrors a single entry in the "results" JSON array written on-device by
 * AtfResultJsonWriter, one per real {@code AccessibilityHierarchyCheckResult}.
 */
@Data
public class AtfCheckResultDto {

    /** Simple class name of the real ATF check, e.g. "TouchTargetSizeCheck". */
    private String checkClass;

    private String checkOriginalClass;

    /** ATF's AccessibilityCheckResultType: ERROR | WARNING | INFO. */
    private String type;

    private int resultId;

    /** Human-readable message exactly as ATF/Accessibility Scanner would show it. */
    private String message;

    /** Relative on-device path, then rewritten to a local absolute path after adb pull. */
    private String screenshotFile;

    private AtfElementDto element;

}
