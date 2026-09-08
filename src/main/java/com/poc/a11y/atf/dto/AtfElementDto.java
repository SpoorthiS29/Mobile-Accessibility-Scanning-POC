package com.poc.a11y.atf.dto;

/** Mirrors the "element" JSON object written by AtfResultJsonWriter on-device. */
public class AtfElementDto {

    private String className;
    private String resourceName;
    private String contentDescription;
    private String text;
    private boolean clickable;
    private boolean enabled;
    private int left;
    private int top;
    private int right;
    private int bottom;

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getContentDescription() { return contentDescription; }
    public void setContentDescription(String contentDescription) { this.contentDescription = contentDescription; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isClickable() { return clickable; }
    public void setClickable(boolean clickable) { this.clickable = clickable; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getLeft() { return left; }
    public void setLeft(int left) { this.left = left; }

    public int getTop() { return top; }
    public void setTop(int top) { this.top = top; }

    public int getRight() { return right; }
    public void setRight(int right) { this.right = right; }

    public int getBottom() { return bottom; }
    public void setBottom(int bottom) { this.bottom = bottom; }
}
