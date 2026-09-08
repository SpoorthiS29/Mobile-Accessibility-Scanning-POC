package com.poc.a11y.model;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Flat representation of a single node from the Appium/UiAutomator2
 * page-source XML dump, plus a derived xpath so findings can point
 * back at a specific element.
 */
@Data
public class UiElement {

    private String xpath;
    private String className;
    private String resourceId;
    private String contentDesc;
    private String text;
    private boolean clickable;
    private boolean focusable;
    private boolean checkable;
    private boolean enabled;
    private boolean password;

    // bounds in device px: [x1,y1][x2,y2]
    private int x1, y1, x2, y2;

    public int widthPx() {
        return Math.max(0, x2 - x1);
    }

    public int heightPx() {
        return Math.max(0, y2 - y1);
    }

    public boolean hasLabel() {
        return (contentDesc != null && !contentDesc.isBlank())
                || (text != null && !text.isBlank());
    }

    public String effectiveLabel() {
        if (contentDesc != null && !contentDesc.isBlank()) return contentDesc;
        if (text != null && !text.isBlank()) return text;
        return "";
    }

    // --- getters / setters ---

}
