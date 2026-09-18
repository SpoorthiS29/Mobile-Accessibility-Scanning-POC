package com.poc.a11y.atf.android;

import com.poc.a11y.cloud.CloudPlatformAdapter;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;

/**
 * Android ATF device-control flow. Local USB uses adb; cloud vendors share
 * Appium {@code mobile: shell} unless a provider needs its own strategy class.
 */
public interface AndroidScanStrategy {

    boolean supports(CloudPlatformAdapter adapter);

    ScanResult scan(ScanRequest request, CloudPlatformAdapter adapter);
}
