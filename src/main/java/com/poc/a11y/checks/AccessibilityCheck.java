package com.poc.a11y.checks;

import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.UiElement;

import java.util.List;

/**
 * One accessibility rule, modeled after the style of checks found in
 * Google's Accessibility Test Framework (ATF). Each check receives the
 * full flat list of elements on screen (some checks need cross-element
 * context, e.g. duplicate-label detection) plus the originating request
 * for tunable thresholds.
 */
public interface AccessibilityCheck {

    String getId();

    List<Issue> evaluate(List<UiElement> elements, ScanRequest request);
}
