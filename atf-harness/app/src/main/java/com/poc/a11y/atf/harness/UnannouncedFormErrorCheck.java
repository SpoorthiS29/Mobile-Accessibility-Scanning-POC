package com.poc.a11y.atf.harness;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
import com.google.android.apps.common.testing.accessibility.framework.uielement.WindowHierarchyElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WCAG 3.3.1 Error Identification (Level A).
 *
 * <p>A static hierarchy cannot prove that TalkBack announces a validation
 * message. This check flags an input when nearby visible text looks like an
 * error, so a reviewer can confirm whether the message is exposed as an
 * accessibility error ({@code EditText.setError()}) or a live region.
 *
 * <p>Heuristic, ported from the XML-dump scanner:
 * <ol>
 *   <li>Collect every {@code EditText} and every other node with visible text.</li>
 *   <li>For each field, keep text whose center is vertically within
 *       {@code max(80px, 2 × field height)} and horizontally within
 *       {@code 2 × field width}.</li>
 *   <li>If that text contains an error keyword, emit one WARNING on the field
 *       (first match only) — not on the message node.</li>
 * </ol>
 */
final class UnannouncedFormErrorCheck implements CustomHierarchyCheck {

    static final String CHECK_NAME = "UnannouncedFormErrorCheck";
    static final int RESULT_ID_NEARBY_ERROR = 1;

    /**
     * Lowercased fragments matched with {@code String.contains}. Kept
     * conservative so ordinary labels like "Email" are not treated as errors.
     */
    static final String[] ERROR_KEYWORDS = {
            "error",
            "invalid",
            "incorrect",
            "required",
            "mandatory",
            "not valid",
            "isn't valid",
            "is not valid",
            "cannot be empty",
            "can't be empty",
            "can not be empty",
            "must not be empty",
            "please enter",
            "please provide",
            "please fill",
            "try again",
            "does not match",
            "doesn't match",
            "did not match",
            "too short",
            "too long",
            "wrong password",
            "wrong email",
            "enter a valid"
    };

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
        List<ViewHierarchyElement> nodes = collectNodes(hierarchy);
        List<ViewHierarchyElement> editTexts = new ArrayList<>();
        List<ViewHierarchyElement> textViews = new ArrayList<>();
        for (ViewHierarchyElement node : nodes) {
            if (isEditText(node)) {
                editTexts.add(node);
            } else if (!charSeq(node.getText()).isEmpty()) {
                textViews.add(node);
            }
        }

        for (ViewHierarchyElement editText : editTexts) {
            int[] et = boundsOrNull(editText);
            if (et == null) {
                continue;
            }
            int etCx = (et[0] + et[2]) / 2;
            int etCy = (et[1] + et[3]) / 2;
            int proximity = Math.max(80, (et[3] - et[1]) * 2);
            int maxDx = (et[2] - et[0]) * 2;

            for (ViewHierarchyElement textView : textViews) {
                int[] tv = boundsOrNull(textView);
                if (tv == null) {
                    continue;
                }
                int tvCx = (tv[0] + tv[2]) / 2;
                int tvCy = (tv[1] + tv[3]) / 2;
                if (Math.abs(tvCy - etCy) > proximity) {
                    continue;
                }
                if (Math.abs(tvCx - etCx) > maxDx) {
                    continue;
                }

                String text = charSeq(textView.getText());
                if (!looksLikeErrorMessage(text)) {
                    continue;
                }

                String msg = "Input field has nearby text that may be an error message (\""
                        + text + "\"). Whether this is actually announced to TalkBack "
                        + "cannot be determined from a static UI dump — verify manually with "
                        + "TalkBack enabled, or use the on-device TalkBack observer to confirm. "
                        + "If it is not announced, use EditText.setError() or "
                        + "ViewCompat.setAccessibilityLiveRegion().";
                AtfIssueRecord record = new AtfIssueRecord(
                        CHECK_NAME,
                        UnannouncedFormErrorCheck.class.getName(),
                        AccessibilityCheckResultType.WARNING,
                        RESULT_ID_NEARBY_ERROR,
                        msg,
                        editText);
                if (cropper != null) {
                    cropper.crop(record);
                }
                findings.add(record);
                break;
            }
        }
        return findings;
    }

    static boolean looksLikeErrorMessage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        for (String keyword : ERROR_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    static boolean isEditText(ViewHierarchyElement node) {
        return node != null && charSeq(node.getClassName()).contains("EditText");
    }

    private static List<ViewHierarchyElement> collectNodes(AccessibilityHierarchy hierarchy) {
        List<ViewHierarchyElement> nodes = new ArrayList<>();
        for (WindowHierarchyElement window : hierarchy.getAllWindows()) {
            if (window == null) {
                continue;
            }
            for (ViewHierarchyElement node : window.getAllViews()) {
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    /** left, top, right, bottom — or null when the node has no usable rect. */
    private static int[] boundsOrNull(ViewHierarchyElement node) {
        if (node == null) {
            return null;
        }
        var bounds = node.getBoundsInScreen();
        if (bounds == null) {
            return null;
        }
        int left = bounds.getLeft();
        int top = bounds.getTop();
        int right = bounds.getRight();
        int bottom = bounds.getBottom();
        if (right <= left || bottom <= top) {
            return null;
        }
        return new int[] {left, top, right, bottom};
    }

    private static String charSeq(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
