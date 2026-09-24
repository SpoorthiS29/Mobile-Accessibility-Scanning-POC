package com.poc.a11y.atf.harness;

import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;

import java.util.List;

/**
 * A per-viewport custom check that runs on the same captured hierarchy,
 * inside the scrolling loop, after the ATF preset checks. When a node is
 * an issue, the check crops it immediately via {@link ViewportCropper}.
 *
 * <p>Device-level checks that change the display (for example Orientation)
 * do not belong here — they run once after the loop.
 *
 * <p>To add a new viewport check: implement this interface and register
 * the class in {@link CustomHierarchyChecks#viewportChecks()}.
 */
interface CustomHierarchyCheck {

    /** Simple name written to JSON as {@code checkClass}, e.g. LabelInNameMismatchCheck. */
    String getCheckName();

    /**
     * Evaluate the already-captured hierarchy. Return only findings to report
     * (typically ERROR/WARNING). Passing nodes should not emit INFO noise.
     * {@code cropper} is invoked on each finding before it is added.
     */
    List<AtfIssueRecord> evaluate(AccessibilityHierarchy hierarchy, ViewportCropper cropper);
}
