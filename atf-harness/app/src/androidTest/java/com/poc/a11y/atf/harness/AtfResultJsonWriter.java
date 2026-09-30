package com.poc.a11y.atf.harness;

import android.graphics.Rect;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
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

        putScreenshot(node, record);

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

        putScreenshot(node, record);

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
        Rect bounds = capturedBounds;
        if (bounds == null) {
            var atfBounds = element.getBoundsInScreen();
            if (atfBounds != null) {
                bounds = new Rect(
                        atfBounds.getLeft(),
                        atfBounds.getTop(),
                        atfBounds.getRight(),
                        atfBounds.getBottom());
            }
        }
        if (bounds != null) {
            el.put("left", bounds.left);
            el.put("top", bounds.top);
            el.put("right", bounds.right);
            el.put("bottom", bounds.bottom);
        }

        String xpath = buildXpath(element);
        if (!xpath.isEmpty()) {
            el.put("xpath", xpath);
        }

        String hierarchy = buildHierarchy(element, bounds);
        if (!hierarchy.isEmpty()) {
            el.put("hierarchy", hierarchy);
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


    private static void putScreenshot(JSONObject node, AtfIssueRecord record)
            throws JSONException {
        if (record.screenshot == null || record.screenshot.isBlank()) {
            return;
        }
        node.put("screenshot", record.screenshot);
    }

    private static String buildHierarchy(ViewHierarchyElement element, Rect bounds) {
        try {
            List<ViewHierarchyElement> chain = new ArrayList<>();
            ViewHierarchyElement current = element;
            int guard = 0;
            while (current != null && guard++ < 64) {
                chain.add(current);
                current = current.getParentView();
            }
            if (chain.isEmpty()) {
                return "";
            }
            Collections.reverse(chain);
            StringBuilder xml = new StringBuilder();
            int last = chain.size() - 1;
            for (int i = 0; i < chain.size(); i++) {
                ViewHierarchyElement node = chain.get(i);
                if (i > 0) {
                    xml.append('\n');
                }
                xml.append("  ".repeat(i));
                xml.append('<').append(classNameOf(node));
                appendAttr(xml, "resource-id", nullToEmpty(node.getResourceName()));
                appendAttr(xml, "text", nullToEmpty(node.getText()));
                appendAttr(xml, "content-desc", nullToEmpty(node.getContentDescription()));
                if (i == last) {
                    xml.append(" clickable=\"").append(node.isClickable()).append('"');
                    xml.append(" enabled=\"").append(node.isEnabled()).append('"');
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        xml.append(" bounds=\"[")
                                .append(bounds.left).append(',').append(bounds.top)
                                .append("][")
                                .append(bounds.right).append(',').append(bounds.bottom)
                                .append("]\"");
                    }
                    xml.append("/>");
                } else {
                    xml.append('>');
                }
            }
            for (int i = last - 1; i >= 0; i--) {
                xml.append('\n');
                xml.append("  ".repeat(i));
                xml.append("</").append(classNameOf(chain.get(i))).append('>');
            }
            return xml.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void appendAttr(StringBuilder xml, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        xml.append(' ').append(name).append("=\"").append(escapeXml(value.trim())).append('"');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String classNameOf(ViewHierarchyElement element) {
        String className = nullToEmpty(element.getClassName()).trim();
        return className.isEmpty() ? "android.view.View" : className;
    }

    /**
     * Appium Inspector xpath. A node with a unique resource-id or content-desc
     * is {@code //Class[@resource-id='id']} or {@code //Class[@content-desc='name']}.
     * A node without one is reached from the nearest ancestor that has one,
     * with a same-class sibling index:
     * {@code //android.widget.Button[@resource-id='scanToShop']/android.view.View[2]}.
     */
    private static String buildXpath(ViewHierarchyElement element) {
        try {
            if (element == null) {
                return "";
            }
            ViewHierarchyElement root = rootOf(element);
            String direct = uniqueAttributePath(element, root);
            if (direct != null) {
                return direct;
            }
            StringBuilder tail = new StringBuilder();
            ViewHierarchyElement current = element;
            int guard = 0;
            while (current.getParentView() != null && guard++ < 64) {
                tail.insert(0, relativeSegment(current));
                ViewHierarchyElement parent = current.getParentView();
                String ancestor = uniqueAttributePath(parent, root);
                if (ancestor != null) {
                    return ancestor + tail;
                }
                current = parent;
            }
            StringBuilder full = new StringBuilder();
            for (ViewHierarchyElement node : chainFromRoot(element)) {
                full.append(relativeSegment(node));
            }
            return full.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** {@code //Class[@resource-id='...']} or {@code //Class[@content-desc='...']} when that pair is unique. */
    private static String uniqueAttributePath(ViewHierarchyElement node, ViewHierarchyElement root) {
        String className = classNameOf(node);
        String resourceId = textOf(node.getResourceName());
        if (!resourceId.isEmpty() && countMatches(root, className, true, resourceId) == 1) {
            return "//" + className + "[@resource-id=" + xpathQuote(resourceId) + "]";
        }
        String description = textOf(node.getContentDescription());
        if (!description.isEmpty() && countMatches(root, className, false, description) == 1) {
            return "//" + className + "[@content-desc=" + xpathQuote(description) + "]";
        }
        return null;
    }

    /**
     * {@code /Class} when this is the only sibling of that class, otherwise
     * {@code /Class[n]} with {@code n} 1-based among those siblings.
     */
    private static String relativeSegment(ViewHierarchyElement node) {
        String segment = "/" + classNameOf(node);
        int index = sameClassSiblingIndex(node);
        if (index > 0) {
            segment += "[" + index + "]";
        }
        return segment;
    }

    private static int sameClassSiblingIndex(ViewHierarchyElement node) {
        ViewHierarchyElement parent = node.getParentView();
        if (parent == null) {
            return 0;
        }
        String className = classNameOf(node);
        int count = 0;
        int index = 0;
        int childCount = parent.getChildViewCount();
        for (int i = 0; i < childCount; i++) {
            ViewHierarchyElement child = parent.getChildView(i);
            if (child == null || !className.equals(classNameOf(child))) {
                continue;
            }
            count++;
            if (child.getId() == node.getId()) {
                index = count;
            }
        }
        return count > 1 ? index : 0;
    }

    private static int countMatches(ViewHierarchyElement root,
                                    String className,
                                    boolean resourceId,
                                    String value) {
        int[] count = {0};
        walk(root, node -> {
            if (!className.equals(classNameOf(node))) {
                return;
            }
            String candidate = resourceId
                    ? textOf(node.getResourceName())
                    : textOf(node.getContentDescription());
            if (value.equals(candidate)) {
                count[0]++;
            }
        });
        return count[0];
    }

    private interface NodeVisitor {
        void visit(ViewHierarchyElement node);
    }

    private static void walk(ViewHierarchyElement node, NodeVisitor visitor) {
        if (node == null) {
            return;
        }
        visitor.visit(node);
        int childCount = node.getChildViewCount();
        for (int i = 0; i < childCount; i++) {
            walk(node.getChildView(i), visitor);
        }
    }

    private static ViewHierarchyElement rootOf(ViewHierarchyElement element) {
        ViewHierarchyElement current = element;
        int guard = 0;
        while (current.getParentView() != null && guard++ < 64) {
            current = current.getParentView();
        }
        return current;
    }

    private static List<ViewHierarchyElement> chainFromRoot(ViewHierarchyElement element) {
        List<ViewHierarchyElement> chain = new ArrayList<>();
        ViewHierarchyElement current = element;
        int guard = 0;
        while (current != null && guard++ < 64) {
            chain.add(current);
            current = current.getParentView();
        }
        Collections.reverse(chain);
        return chain;
    }

    private static String textOf(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String xpathQuote(String value) {
        if (value.indexOf('\'') >= 0 && value.indexOf('"') >= 0) {
            return "concat('" + value.replace("'", "', \"'\", '") + "')";
        }
        if (value.indexOf('\'') >= 0) {
            return "\"" + value + "\"";
        }
        return "'" + value + "'";
    }

    private static String nullToEmpty(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}