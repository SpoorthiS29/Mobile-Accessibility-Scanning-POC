package com.poc.a11y.ios;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IosIssueDeduplicatorTest {

    @Test
    void dropsSameElementThatReappearsAfterScrollIgnoringFrame() {
        IosIssueDeduplicator deduplicator = new IosIssueDeduplicator();

        Map<String, Object> first = issue("Button", "Cart", "XCUIAccessibilityAuditTypeHitRegion", 10, 700);
        Map<String, Object> sameAfterScroll = issue("Button", "Cart", "XCUIAccessibilityAuditTypeHitRegion", 10, 80);
        Map<String, Object> contrastOnSame = issue("Button", "Cart", "XCUIAccessibilityAuditTypeContrast", 10, 80);
        Map<String, Object> other = issue("StaticText", "Price", "XCUIAccessibilityAuditTypeHitRegion", 40, 200);

        List<Map<String, Object>> viewports = new ArrayList<>();
        viewports.add(viewport(0, List.of(first, other)));
        viewports.add(viewport(1, List.of(sameAfterScroll, contrastOnSame)));

        int removed = deduplicator.dedupeViewports(viewports);

        assertEquals(1, removed);
        assertEquals(2, ((List<?>) viewports.get(0).get("issues")).size());
        List<?> second = (List<?>) viewports.get(1).get("issues");
        assertEquals(1, second.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> kept = (Map<String, Object>) second.get(0);
        assertEquals("XCUIAccessibilityAuditTypeContrast", kept.get("auditType"));
    }

    private static Map<String, Object> viewport(int index, List<Map<String, Object>> issues) {
        Map<String, Object> viewport = new HashMap<>();
        viewport.put("index", index);
        viewport.put("issues", new ArrayList<>(issues));
        return viewport;
    }

    private static Map<String, Object> issue(String type, String name, String auditType, int x, int y) {
        Map<String, Object> rect = new HashMap<>();
        rect.put("x", x);
        rect.put("y", y);
        rect.put("width", 48);
        rect.put("height", 48);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("type", type);
        attrs.put("name", name);
        attrs.put("label", name);
        attrs.put("rect", rect);
        Map<String, Object> issue = new HashMap<>();
        issue.put("auditType", auditType);
        issue.put("compactDescription", name + " is too small");
        issue.put("elementAttributes", attrs);
        return issue;
    }
}
