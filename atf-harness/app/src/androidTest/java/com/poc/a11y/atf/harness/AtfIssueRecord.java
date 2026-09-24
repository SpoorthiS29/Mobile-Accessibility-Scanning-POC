package com.poc.a11y.atf.harness;

import android.graphics.Rect;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;

/**
 * One accessibility finding.
 *
 * The record can represent either:
 * 1. A native ATF result, or
 * 2. A custom accessibility/WCAG result.
 *
 * For ATF results, {@link #result} is populated.
 * For custom results, {@link #result} is null and the custom fields are used.
 */
final class AtfIssueRecord {

    // ============================================================
    // ATF RESULT
    // ============================================================

    final AccessibilityHierarchyCheckResult result;


    // ============================================================
    // CUSTOM RESULT
    // ============================================================

    /** Custom check name, e.g. OrientationCheck. */
    String customCheckClass;

    /** Fully qualified custom check class name, when available. */
    String customCheckOriginalClass;

    /** Result type: ERROR, WARNING, INFO, NOT_RUN, SUPPRESSED. */
    AccessibilityCheckResult.AccessibilityCheckResultType customType;

    /** Custom result identifier, e.g. WCAG_1_3_4. */
    int customResultId;

    /** Human-readable custom result message. */
    String customMessage;

    /** Failing widget for per-element custom checks. Null for device-level checks. */
    ViewHierarchyElement customElement;


    // ============================================================
    // COMMON RESULT DATA
    // ============================================================

    /** Relative path to the cropped screenshot, if available. */
    String screenshotFile;

    /** Where the widget actually was when the screenshot was taken. */
    Rect capturedBounds;

    /**
     * Widget bounds in document space.
     *
     * Used for cross-viewport deduplication.
     */
    Rect documentBounds;

    private int viewport;


    // ============================================================
    // ATF CONSTRUCTOR
    // ============================================================

    AtfIssueRecord(AccessibilityHierarchyCheckResult result,
                   String screenshotFile) {

        this.result = result;
        this.screenshotFile = screenshotFile;
    }


    // ============================================================
    // CUSTOM CONSTRUCTOR
    // ============================================================

    AtfIssueRecord(String customCheckClass,
                   String customCheckOriginalClass,
                   AccessibilityCheckResult.AccessibilityCheckResultType customType,
                   int customResultId,
                   String customMessage) {

        this(customCheckClass, customCheckOriginalClass, customType, customResultId, customMessage, null);
    }

    AtfIssueRecord(String customCheckClass,
                   String customCheckOriginalClass,
                   AccessibilityCheckResult.AccessibilityCheckResultType customType,
                   int customResultId,
                   String customMessage,
                   ViewHierarchyElement customElement) {

        this.result = null;

        this.customCheckClass = customCheckClass;
        this.customCheckOriginalClass = customCheckOriginalClass;
        this.customType = customType;
        this.customResultId = customResultId;
        this.customMessage = customMessage;
        this.customElement = customElement;
    }


    // ============================================================
    // HELPERS
    // ============================================================

    boolean isCustomResult() {
        return result == null;
    }

    boolean isAtfResult() {
        return result != null;
    }

    /**
     * Widget for crop / identity / JSON, whether this came from ATF or a
     * per-element custom check. Null for device-level custom results.
     */
    ViewHierarchyElement getElement() {
        if (result != null) {
            return result.getElement();
        }
        return customElement;
    }

    String getCheckClassName() {
        if (result != null && result.getSourceCheckClass() != null) {
            return result.getSourceCheckClass().getSimpleName();
        }
        return customCheckClass != null ? customCheckClass : "";
    }

    AccessibilityCheckResultType getResultType() {
        if (result != null) {
            return result.getType();
        }
        return customType;
    }

    int getResultIdValue() {
        if (result != null) {
            return result.getResultId();
        }
        return customResultId;
    }

    public int getViewport() {
        return viewport;
    }

    public void setViewport(int viewport) {
        this.viewport = viewport;
    }
}