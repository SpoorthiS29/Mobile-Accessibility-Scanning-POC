package com.poc.a11y.atf.harness;

import android.graphics.Rect;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;

/** One ATF finding plus an optional cropped element screenshot (relative path). */
final class AtfIssueRecord {

    final AccessibilityHierarchyCheckResult result;
    String screenshotFile;

    /** Where the widget actually was when the screenshot was taken, if resolved. */
    Rect capturedBounds;

    /**
     * The widget's bounds in document space (screen bounds + total scroll so
     * far at capture time), used to recognize the same element across a
     * scroll for cross-viewport dedupe. Screen bounds alone can't do this
     * since the same element sits at a different Y after a swipe.
     */
    Rect documentBounds;
    private int viewport;

    AtfIssueRecord(AccessibilityHierarchyCheckResult result, String screenshotFile) {
        this.result = result;
        this.screenshotFile = screenshotFile;
    }

    public int getViewport() {
        return viewport;
    }

    public void setViewport(int viewport) {
        this.viewport = viewport;
    }
}