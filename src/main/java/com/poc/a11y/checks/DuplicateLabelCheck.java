package com.poc.a11y.checks;

import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.Severity;
import com.poc.a11y.model.UiElement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags interactive elements at different screen locations that share the
 * exact same accessible label, which makes them indistinguishable to a
 * screen reader user even though they're visually and functionally distinct
 * (e.g. multiple icon buttons all announced as "Button").
 */
@Component
public class DuplicateLabelCheck implements AccessibilityCheck {

    @Override
    public String getId() {
        return "DUPLICATE_ACCESSIBLE_LABEL";
    }

    @Override
    public List<Issue> evaluate(List<UiElement> elements, ScanRequest request) {
        Map<String, List<UiElement>> byLabel = new LinkedHashMap<>();

        for (UiElement el : elements) {
            if (!el.isClickable() || !el.isEnabled() || !el.hasLabel()) continue;
            byLabel.computeIfAbsent(el.effectiveLabel(), k -> new ArrayList<>()).add(el);
        }

        List<Issue> issues = new ArrayList<>();
        for (Map.Entry<String, List<UiElement>> entry : byLabel.entrySet()) {
            List<UiElement> group = entry.getValue();
            if (group.size() < 2) continue;

            boolean distinctLocations = group.stream()
                    .map(e -> e.getX1() + "," + e.getY1())
                    .distinct().count() > 1;
            if (!distinctLocations) continue;

            for (UiElement el : group) {
                issues.add(new Issue(
                        getId(),
                        "Ambiguous duplicate accessible label",
                        group.size() + " interactive elements share the label \"" + entry.getKey() +
                                "\" at different positions on screen; a screen reader user cannot tell them apart.",
                        Severity.MODERATE,
                        el,
                        "4.1.2 Name, Role, Value"
                ));
            }
        }
        return issues;
    }
}
