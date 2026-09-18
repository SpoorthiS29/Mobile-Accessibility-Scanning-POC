package com.poc.a11y.cloud;

/**
 * Recognizes already-uploaded app references used by Optimize / Appium:
 * Sauce {@code storage:}, BrowserStack {@code bs://}, LambdaTest {@code lt://}.
 */
public final class CloudAppRefs {

    private CloudAppRefs() {
    }

    public static boolean isRemoteRef(String appPath) {
        if (appPath == null || appPath.isBlank()) {
            return false;
        }
        String value = appPath.trim();
        return value.regionMatches(true, 0, "storage:", 0, "storage:".length())
                || value.regionMatches(true, 0, "bs://", 0, "bs://".length())
                || value.regionMatches(true, 0, "lt://", 0, "lt://".length());
    }

    public static boolean isLocalDefaultAppiumUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String trimmed = url.trim().toLowerCase();
        return trimmed.contains("127.0.0.1") || trimmed.contains("localhost");
    }
}
