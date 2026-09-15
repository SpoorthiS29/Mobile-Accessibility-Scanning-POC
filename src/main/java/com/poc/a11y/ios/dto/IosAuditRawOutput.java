package com.poc.a11y.ios.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory handle to raw XCUIAccessibilityAudit output written under {@code ios-results/}.
 * Each viewport map keeps Appium's issue objects unchanged plus a local screenshot path.
 */
@Data
public class IosAuditRawOutput {

    private String rawJsonPath;
    private List<Map<String, Object>> viewports = new ArrayList<>();
}
