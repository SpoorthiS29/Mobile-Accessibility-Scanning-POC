package com.poc.a11y.ios;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops the same XCUIAccessibilityAudit finding when it reappears after a
 * scroll (sticky chrome, overlapping viewports). Identity ignores frame/rect
 * so an element that moved with the scroll still matches.
 */
@Component
public class IosIssueDeduplicator {

    @SuppressWarnings("unchecked")
    public int dedupeViewports(List<Map<String, Object>> viewports) {
        if (viewports == null || viewports.isEmpty()) {
            return 0;
        }
        Set<String> seen = new LinkedHashSet<>();
        int removed = 0;
        for (Map<String, Object> viewport : viewports) {
            Object batchObj = viewport.get("issues");
            if (!(batchObj instanceof List<?> batch)) {
                continue;
            }
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Object item : batch) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> issue = (Map<String, Object>) map;
                if (seen.add(identity(issue))) {
                    kept.add(issue);
                } else {
                    removed++;
                }
            }
            viewport.put("issues", kept);
        }
        return removed;
    }

    static String identity(Map<String, Object> issue) {
        Map<String, Object> attrs = attributes(issue);
        return String.join("|",
                nullToEmpty(str(issue.get("auditType"))),
                nullToEmpty(str(issue.get("compactDescription"))),
                attrs == null ? "" : nullToEmpty(str(attrs.get("type"))),
                attrs == null ? "" : nullToEmpty(str(attrs.get("name"))),
                attrs == null ? "" : nullToEmpty(str(attrs.get("label"))),
                attrs == null ? "" : nullToEmpty(str(attrs.get("value"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> issue) {
        Object attrs = issue.get("elementAttributes");
        if (attrs instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
