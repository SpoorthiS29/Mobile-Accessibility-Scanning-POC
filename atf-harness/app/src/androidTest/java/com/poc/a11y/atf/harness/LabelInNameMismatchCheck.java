package com.poc.a11y.atf.harness;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
import com.google.android.apps.common.testing.accessibility.framework.uielement.WindowHierarchyElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WCAG 2.5.3 Label in Name (Level A).
 *
 * <p>Speech-input users activate controls by saying the visible label.
 * On Android, a non-empty {@code contentDescription} becomes the accessible
 * name and replaces the visible text. If that name does not contain the
 * visible text, "Click Submit" will not hit a button whose name is "Send".
 *
 * <p>Only interactive nodes that expose both a visible label and a
 * content description are evaluated. A missing description is not a 2.5.3
 * failure (the platform then uses the visible text as the name).
 */
final class LabelInNameMismatchCheck implements CustomHierarchyCheck {

    static final String CHECK_NAME = "LabelInNameMismatchCheck";
    static final int RESULT_ID_MISMATCH = 1;

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
        for (WindowHierarchyElement window : hierarchy.getAllWindows()) {
            if (window == null) {
                continue;
            }
            for (ViewHierarchyElement node : window.getAllViews()) {
                checkNode(node, findings, cropper);
            }
        }
        return findings;
    }

    private static void checkNode(ViewHierarchyElement node, List<AtfIssueRecord> findings,
                                  ViewportCropper cropper) {
        if (node == null) {
            return;
        }
        if (!node.isClickable() && !node.isFocusable()) {
            return;
        }

        String text = charSeq(node.getText());
        String contentDesc = charSeq(node.getContentDescription());
        if (text.isEmpty() || contentDesc.isEmpty()) {
            return;
        }

        String normText = normalizeLabel(text);
        String normDesc = normalizeLabel(contentDesc);
        if (!hasAtLeastTwoLetters(normText)) {
            return;
        }

        if (!normDesc.contains(normText)) {
            String msg = "Visible label \"" + text + "\" does not appear in the "
                    + "accessible name (\"" + contentDesc + "\"). Voice Access / "
                    + "speech-input users who say the visible label will not be able to "
                    + "activate this control. Ensure contentDescription contains the "
                    + "visible text (WCAG 2.5.3 Label in Name).";
            AtfIssueRecord record = new AtfIssueRecord(
                    CHECK_NAME,
                    LabelInNameMismatchCheck.class.getName(),
                    AccessibilityCheckResultType.ERROR,
                    RESULT_ID_MISMATCH,
                    msg,
                    node);
            if (cropper != null) {
                cropper.crop(record);
            }
            findings.add(record);
        }
    }

    /** Lowercase and strip everything except ASCII letters and digits. */
    static String normalizeLabel(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    static boolean hasAtLeastTwoLetters(String normalized) {
        int letters = 0;
        for (char c : normalized.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
            }
            if (letters >= 2) {
                return true;
            }
        }
        return false;
    }

    private static String charSeq(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
