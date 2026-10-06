package com.fireflink.a11y.atf;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
import com.google.android.apps.common.testing.accessibility.framework.uielement.WindowHierarchyElement;

import java.util.ArrayList;
import java.util.List;

/**
 * WCAG 2.4.3 Focus Order (Level A).
 *
 * <p>Detects possible illogical focus/traversal order by looking for
 * significant backward jumps in the document order of clickable/focusable
 * elements.
 *
 * <p>This is a heuristic based on accessibility hierarchy/document order.
 * It does not observe actual TalkBack traversal and therefore requires
 * manual verification.
 */
final class IllogicalFocusOrderCheck implements CustomHierarchyCheck {

    static final String CHECK_NAME = "IlLogicalFocusOrder";
    static final int RESULT_ID_FOCUS_ORDER = 1;

    private static final String TAG = "IllogicalFocusOrderCheck";

    /**
     * Large backward movement required before considering a jump.
     * <p>
     * Equivalent to the old:
     * <p>
     * maxBottom * 0.35
     */
    private static final double Y_BACKWARD_THRESHOLD = 0.35;

    /**
     * Significant movement to the right is treated as a possible
     * legitimate column/grid transition.
     * <p>
     * Equivalent to the old:
     * <p>
     * maxRight * 0.15
     */
    private static final double X_COLUMN_THRESHOLD = 0.15;

    /**
     * For smaller screens/lists, require at least two violations.
     */
    private static final int MIN_VIOLATIONS = 2;

    /**
     * For long lists/grids, require three violations.
     */
    private static final int LARGE_SCREEN_MIN_VIOLATIONS = 3;

    /**
     * Number of candidates at which the stricter threshold applies.
     */
    private static final int LARGE_CANDIDATE_COUNT = 15;

    @Override
    public String getCheckName() {
        return CHECK_NAME;
    }

    @Override
    public List<AtfIssueRecord> evaluate(AccessibilityHierarchy hierarchy, ViewportCropper cropper) {

        List<AtfIssueRecord> findings = new ArrayList<>();

        if (hierarchy == null) {
            return findings;
        }

        /*
         * Evaluate each accessibility window independently.
         *
         * The old implementation received one screen's node list.
         * Here each ATF WindowHierarchyElement represents that scope.
         */
        for (WindowHierarchyElement window : hierarchy.getAllWindows()) {

            if (window == null) {
                continue;
            }

            List<ViewHierarchyElement> nodes = (List<ViewHierarchyElement>) window.getAllViews();

            if (nodes == null || nodes.size() < 3) {
                continue;
            }

            AtfIssueRecord finding =
                    checkFocusOrder(nodes, cropper);

            if (finding != null) {
                findings.add(finding);
            }
        }

        return findings;
    }

    private static AtfIssueRecord checkFocusOrder(
            List<ViewHierarchyElement> nodes,
            ViewportCropper cropper) {

        /*
         * ------------------------------------------------------------
         * 1. Identify traversal candidates.
         *
         * Include clickable elements too, not just focusable=true.
         * TalkBack can traverse custom clickable controls even when
         * focusable is not explicitly set.
         * ------------------------------------------------------------
         */
        List<ViewHierarchyElement> candidates = new ArrayList<>();

        for (ViewHierarchyElement node : nodes) {

            if (node == null) {
                continue;
            }

            if (!node.isFocusable() && !node.isClickable()) {
                continue;
            }

            var bounds = node.getBoundsInScreen();

            if (bounds == null) {
                continue;
            }

            if (bounds.getBottom() <= bounds.getTop()) {
                continue;
            }

            candidates.add(node);
        }

        if (candidates.size() < 3) {
            return null;
        }

        /*
         * ------------------------------------------------------------
         * 2. Calculate the full content extent.
         *
         * IMPORTANT:
         * Use ALL nodes, not only focusable/clickable candidates.
         * This preserves the old implementation's behaviour.
         * ------------------------------------------------------------
         */
        int maxBottom = 0;
        int maxRight = 0;

        for (ViewHierarchyElement node : nodes) {

            if (node == null) {
                continue;
            }

            var bounds = node.getBoundsInScreen();

            if (bounds == null) {
                continue;
            }

            maxBottom = Math.max(
                    maxBottom,
                    bounds.getBottom()
            );

            maxRight = Math.max(
                    maxRight,
                    bounds.getRight()
            );
        }

        if (maxBottom <= 0) {
            return null;
        }

        /*
         * ------------------------------------------------------------
         * 3. Calculate thresholds.
         *
         * Old:
         *
         * yThreshold = maxBottom * 0.35
         * xColumnThreshold = maxRight * 0.15
         * ------------------------------------------------------------
         */
        int yThreshold =
                (int) (maxBottom * Y_BACKWARD_THRESHOLD);

        int xColumnThreshold =
                (int) (maxRight * X_COLUMN_THRESHOLD);

        int violations = 0;
        ViewHierarchyElement firstBad = null;

        /*
         * ------------------------------------------------------------
         * 4. Compare consecutive traversal candidates.
         *
         * Accessibility hierarchy order is used as the document order.
         * ------------------------------------------------------------
         */
        for (int i = 1; i < candidates.size(); i++) {

            ViewHierarchyElement previous =
                    candidates.get(i - 1);

            ViewHierarchyElement current =
                    candidates.get(i);

            var previousBounds =
                    previous.getBoundsInScreen();

            var currentBounds =
                    current.getBoundsInScreen();

            if (previousBounds == null
                    || currentBounds == null) {
                continue;
            }

            int previousCenterY =
                    (previousBounds.getTop()
                            + previousBounds.getBottom()) / 2;

            int currentCenterY =
                    (currentBounds.getTop()
                            + currentBounds.getBottom()) / 2;

            int previousCenterX =
                    (previousBounds.getLeft()
                            + previousBounds.getRight()) / 2;

            int currentCenterX =
                    (currentBounds.getLeft()
                            + currentBounds.getRight()) / 2;

            /*
             * Large backward vertical movement.
             */
            boolean bigBackwardJump =
                    (previousCenterY - currentCenterY)
                            > yThreshold;

            /*
             * Significant movement to the right can indicate a
             * legitimate column/grid transition.
             */
            boolean looksLikeColumnShift =
                    (currentCenterX - previousCenterX)
                            > xColumnThreshold;

            if (bigBackwardJump && !looksLikeColumnShift) {

                violations++;

                if (firstBad == null) {
                    firstBad = current;
                }
            }
        }

        /*
         * ------------------------------------------------------------
         * 5. Require enough evidence before reporting.
         *
         * Old:
         *
         * candidates >= 15 -> 3 violations
         * otherwise        -> 2 violations
         * ------------------------------------------------------------
         */
        int minViolations =
                candidates.size() >= LARGE_CANDIDATE_COUNT
                        ? LARGE_SCREEN_MIN_VIOLATIONS
                        : MIN_VIOLATIONS;

        if (violations < minViolations
                || firstBad == null) {
            return null;
        }

        /*
         * ------------------------------------------------------------
         * 6. Create finding.
         * ------------------------------------------------------------
         */
        String message =
                "Possible illogical focus traversal order: "
                        + violations
                        + " large backward jump(s) detected in "
                        + "document reading order (first occurrence "
                        + "highlighted). This is a heuristic based on "
                        + "document order, not actual TalkBack traversal "
                        + "— verify manually with TalkBack before treating "
                        + "as a defect. If confirmed, review "
                        + "android:accessibilityTraversalBefore / After.";

        AtfIssueRecord record =
                new AtfIssueRecord(
                        CHECK_NAME,
                        IllogicalFocusOrderCheck.class.getName(),
                        AccessibilityCheckResultType.WARNING,
                        RESULT_ID_FOCUS_ORDER,
                        message,
                        firstBad
                );

        /*
         * Crop the first detected problematic element.
         */
        if (cropper != null) {
            cropper.crop(record);
        }

        return record;
    }
}