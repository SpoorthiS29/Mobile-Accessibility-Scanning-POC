package com.poc.a11y.checks;

import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.Severity;
import com.poc.a11y.model.UiElement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent of ATF's SpeakableTextPresentCheck: any interactive element
 * (clickable/checkable) must expose either text or a content-description,
 * otherwise a screen reader has nothing to announce.
 */
@Component
public class MissingContentDescCheck implements AccessibilityCheck {

    @Override
    public String getId() {
        return "MISSING_ACCESSIBLE_NAME";
    }

    @Override
    public List<Issue> evaluate(List<UiElement> elements, ScanRequest request) {
        List<Issue> issues = new ArrayList<>();
        for (UiElement el : elements) {
            boolean interactive = el.isClickable() || el.isCheckable();
            if (interactive && el.isEnabled() && !el.hasLabel()) {
                issues.add(new Issue(
                        getId(),
                        "Interactive element has no accessible name",
                        "Element <" + el.getClassName() + "> is clickable/checkable but exposes no " +
                                "content-description or text, so a screen reader cannot announce it.",
                        Severity.CRITICAL,
                        el,
                        "4.1.2 Name, Role, Value"
                ));
            }
        }
        return issues;
    }
}
