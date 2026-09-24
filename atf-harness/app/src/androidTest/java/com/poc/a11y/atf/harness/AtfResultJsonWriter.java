package com.poc.a11y.atf.harness;

import android.graphics.Rect;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

final class AtfResultJsonWriter {

    private AtfResultJsonWriter() {
    }

    static String toJson(List<AtfIssueRecord> records,
                         float density,
                         int viewportCount)
            throws JSONException {

        JSONObject root = new JSONObject();

        root.put("density", density);
        root.put("viewportCount", viewportCount);

        JSONArray array = new JSONArray();

        for (AtfIssueRecord record : records) {

            if (record == null) {
                continue;
            }

            /*
             * Custom result:
             *
             * result == null by design.
             * Therefore do NOT call result.getType().
             */
            if (record.isCustomResult()) {

                AccessibilityCheckResultType type = record.customType;

                if (type == AccessibilityCheckResultType.NOT_RUN
                        || type == AccessibilityCheckResultType.SUPPRESSED) {
                    continue;
                }

                array.put(toJsonCustomResult(record));
                continue;
            }

            /*
             * Native ATF result.
             */
            AccessibilityHierarchyCheckResult result = record.result;

            if (result == null) {
                continue;
            }

            AccessibilityCheckResultType type = result.getType();

            if (type == AccessibilityCheckResultType.NOT_RUN
                    || type == AccessibilityCheckResultType.SUPPRESSED) {
                continue;
            }

            array.put(toJsonAtfResult(record));
        }

        root.put("resultCount", array.length());
        root.put("results", array);

        return root.toString();
    }


    // ============================================================
    // ATF RESULT
    // ============================================================

    private static JSONObject toJsonAtfResult(AtfIssueRecord record)
            throws JSONException {

        AccessibilityHierarchyCheckResult result = record.result;

        JSONObject node = new JSONObject();

        AccessibilityCheckResultType type = result.getType();

        node.put(
                "checkClass",
                result.getSourceCheckClass().getSimpleName()
        );

        node.put(
                "checkOriginalClass",
                result.getSourceCheckClass().getName()
        );

        /*
         * No filtering.
         *
         * This can now be:
         *
         * ERROR
         * WARNING
         * INFO
         * NOT_RUN
         * SUPPRESSED
         */
        node.put("type", type.name());

        node.put(
                "resultId",
                result.getResultId()
        );

        node.put(
                "message",
                String.valueOf(result.getMessage(Locale.US))
        );

        node.put(
                "viewport",
                record.getViewport()
        );

        if (record.screenshotFile != null) {
            node.put(
                    "screenshotFile",
                    record.screenshotFile
            );
        }

        ViewHierarchyElement element = result.getElement();

        if (element != null) {
            node.put(
                    "element",
                    toJsonElement(
                            element,
                            record.capturedBounds
                    )
            );
        }

        return node;
    }


    // ============================================================
    // CUSTOM RESULT
    // ============================================================

    private static JSONObject toJsonCustomResult(AtfIssueRecord record)
            throws JSONException {

        JSONObject node = new JSONObject();

        node.put(
                "checkClass",
                nullToEmpty(record.customCheckClass)
        );

        node.put(
                "checkOriginalClass",
                nullToEmpty(record.customCheckOriginalClass)
        );

        node.put(
                "type",
                record.customType != null ? record.customType.name() : ""
        );

        node.put(
                "resultId",
                record.customResultId
        );

        node.put(
                "message",
                nullToEmpty(record.customMessage)
        );

        node.put(
                "viewport",
                record.getViewport()
        );

        if (record.screenshotFile != null) {
            node.put(
                    "screenshotFile",
                    record.screenshotFile
            );
        }

        ViewHierarchyElement element = record.getElement();
        if (element != null) {
            node.put(
                    "element",
                    toJsonElement(element, record.capturedBounds)
            );
        } else if (record.capturedBounds != null) {
            node.put(
                    "elementBounds",
                    toJsonBounds(record.capturedBounds)
            );
        }

        return node;
    }


    // ============================================================
    // ELEMENT
    // ============================================================

    private static JSONObject toJsonElement(
            ViewHierarchyElement element,
            Rect capturedBounds)
            throws JSONException {

        JSONObject el = new JSONObject();

        el.put(
                "className",
                nullToEmpty(element.getClassName())
        );

        el.put(
                "resourceName",
                nullToEmpty(element.getResourceName())
        );

        el.put(
                "contentDescription",
                nullToEmpty(element.getContentDescription())
        );

        el.put(
                "text",
                nullToEmpty(element.getText())
        );

        el.put(
                "clickable",
                element.isClickable()
        );

        el.put(
                "enabled",
                element.isEnabled()
        );

        /*
         * Prefer the bounds sampled next to the screenshot.
         */
        if (capturedBounds != null) {

            el.put("left", capturedBounds.left);
            el.put("top", capturedBounds.top);
            el.put("right", capturedBounds.right);
            el.put("bottom", capturedBounds.bottom);

        } else {

            var bounds = element.getBoundsInScreen();

            if (bounds != null) {
                el.put("left", bounds.getLeft());
                el.put("top", bounds.getTop());
                el.put("right", bounds.getRight());
                el.put("bottom", bounds.getBottom());
            }
        }

        return el;
    }


    // ============================================================
    // CUSTOM BOUNDS
    // ============================================================

    private static JSONObject toJsonBounds(Rect bounds)
            throws JSONException {

        JSONObject json = new JSONObject();

        json.put("left", bounds.left);
        json.put("top", bounds.top);
        json.put("right", bounds.right);
        json.put("bottom", bounds.bottom);

        return json;
    }


    private static String nullToEmpty(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}