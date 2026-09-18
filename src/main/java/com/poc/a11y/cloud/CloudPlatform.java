package com.poc.a11y.cloud;

import java.util.Locale;

/**
 * Execution target for Appium. Add a new enum value and a
 * {@link CloudPlatformAdapter} {@code @Component} when a provider is wired in.
 * Names match Optimize's {@code CloudPlatforms} tokens where possible
 * ({@code saucelabs}, {@code browserstack}, {@code lambdatest}).
 */
public enum CloudPlatform {
    LOCAL,
    SAUCE_LABS,
    BROWSERSTACK,
    LAMBDATEST;

    public static CloudPlatform from(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        return switch (normalized) {
            case "local", "localhost", "usb", "physical", "none" -> LOCAL;
            case "sauce", "saucelabs", "sauce-labs", "sauce-lab" -> SAUCE_LABS;
            case "browserstack", "browser-stack", "bstack" -> BROWSERSTACK;
            case "lambdatest", "lambda-test", "lt" -> LAMBDATEST;
            default -> throw new IllegalArgumentException(
                    "Unknown cloudPlatform: " + raw
                            + ". Use local, sauceLabs, browserStack, or lambdaTest.");
        };
    }

    public boolean isLocal() {
        return this == LOCAL;
    }
}
