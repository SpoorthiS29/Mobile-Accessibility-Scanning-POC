package com.poc.a11y.atf;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for invoking the real-ATF instrumentation harness
 * (see the sibling {@code atf-harness/} Gradle project) via adb.
 * Bound from the {@code atf.*} keys in application.yml.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "atf")
public class AtfProperties {

    private String adbPath = "adb";
    private String harnessTestPackage = "com.poc.a11y.atf.harness.test";
    private String harnessInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner";
    private String harnessTestClass = "com.poc.a11y.atf.harness.AtfScanTest";
    private String deviceResultPath = "/sdcard/Android/data/com.poc.a11y.atf.harness/files/atf-result.json";
    private String deviceShotsPath = "/sdcard/Android/data/com.poc.a11y.atf.harness/files/shots";
    private String appiumServerUrl = "http://127.0.0.1:4723";
    private int amInstrumentTimeoutSeconds = 300;

    private String harnessApkPath = "atf-harness/app/build/outputs/apk/debug/app-debug.apk";
    private String harnessTestApkPath = "atf-harness/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk";
    private String harnessPackage = "com.poc.a11y.atf.harness";

}
