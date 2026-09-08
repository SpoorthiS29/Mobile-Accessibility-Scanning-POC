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

    static String toJson(List<AtfIssueRecord> records, float density, int viewportCount)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("density", density);
        root.put("viewportCount", viewportCount);

        JSONArray array = new JSONArray();
        for (AtfIssueRecord record : records) {
            AccessibilityHierarchyCheckResult result = record.result;
            AccessibilityCheckResultType type = result.getType();
            if (type == AccessibilityCheckResultType.NOT_RUN
                    || type == AccessibilityCheckResultType.SUPPRESSED) {
                continue;
            }
            array.put(toJsonResult(record, type));
        }

        root.put("resultCount", array.length());
        root.put("results", array);
        return root.toString();
    }

    private static JSONObject toJsonResult(AtfIssueRecord record, AccessibilityCheckResultType type)
            throws JSONException {
        AccessibilityHierarchyCheckResult result = record.result;
        JSONObject node = new JSONObject();
        node.put("checkClass", result.getSourceCheckClass().getSimpleName());
        node.put("checkOriginalClass", result.getSourceCheckClass().getName());
        node.put("type", type.name());
        node.put("resultId", result.getResultId());
        node.put("message", String.valueOf(result.getMessage(Locale.US)));

        if (record.screenshotFile != null) {
            node.put("screenshotFile", record.screenshotFile);
        }

        ViewHierarchyElement element = result.getElement();
        if (element != null) {
            node.put("element", toJsonElement(element, record.capturedBounds));
        }
        return node;
    }

    private static JSONObject toJsonElement(ViewHierarchyElement element, Rect capturedBounds)
            throws JSONException {
        JSONObject el = new JSONObject();
        el.put("className", nullToEmpty(element.getClassName()));
        el.put("resourceName", nullToEmpty(element.getResourceName()));
        el.put("contentDescription", nullToEmpty(element.getContentDescription()));
        el.put("text", nullToEmpty(element.getText()));
        el.put("clickable", element.isClickable());
        el.put("enabled", element.isEnabled());

        // Prefer the rect sampled next to the screenshot so the reported
        // coordinates describe the same frame as the crop.
        if (capturedBounds != null) {
            el.put("left", capturedBounds.left);
            el.put("top", capturedBounds.top);
            el.put("right", capturedBounds.right);
            el.put("bottom", capturedBounds.bottom);
        } else {
            var bounds = element.getBoundsInScreen();
            el.put("left", bounds.getLeft());
            el.put("top", bounds.getTop());
            el.put("right", bounds.getRight());
            el.put("bottom", bounds.getBottom());
        }

        return el;
    }

    private static String nullToEmpty(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
