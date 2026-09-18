package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudPlatformTest {

    @Test
    void parsesVendorAliases() {
        assertEquals(CloudPlatform.LOCAL, CloudPlatform.from("usb"));
        assertEquals(CloudPlatform.SAUCE_LABS, CloudPlatform.from("sauceLabs"));
        assertEquals(CloudPlatform.BROWSERSTACK, CloudPlatform.from("browser_stack"));
        assertEquals(CloudPlatform.LAMBDATEST, CloudPlatform.from("lt"));
    }

    @Test
    void virtualDeviceWithoutCloudPlatformIsSauceLabs() {
        ScanRequest request = new ScanRequest();
        request.setVirtualDevice(true);
        assertEquals(CloudPlatform.SAUCE_LABS, request.resolveCloudPlatform());
    }

    @Test
    void explicitCloudPlatformWinsOverVirtualDeviceFlag() {
        ScanRequest request = new ScanRequest();
        request.setVirtualDevice(true);
        request.setCloudPlatform("browserStack");
        assertEquals(CloudPlatform.BROWSERSTACK, request.resolveCloudPlatform());
    }

    @Test
    void rejectsUnknownPlatform() {
        assertThrows(IllegalArgumentException.class, () -> CloudPlatform.from("perfecto"));
    }
}
