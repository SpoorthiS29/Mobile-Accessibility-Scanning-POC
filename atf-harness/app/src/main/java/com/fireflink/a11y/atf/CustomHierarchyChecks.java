package com.fireflink.a11y.atf;

import java.util.List;

/**
 * Registry of per-viewport custom checks. Append a new instance here when
 * adding another WCAG rule that should run on every viewport after ATF.
 */
final class CustomHierarchyChecks {

    private CustomHierarchyChecks() {
    }

    static List<CustomHierarchyCheck> viewportChecks() {
        return List.of(
                new LabelInNameMismatchCheck(),
                new UnannouncedFormErrorCheck(),
                new LowIconContrastCheck(),
                new IllogicalFocusOrderCheck()
        );
    }
}
