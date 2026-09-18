package com.poc.a11y.ios;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Local XCUITest WDA project used by FireFlink Client / Optimize
 * ({@code agentPath} / {@code bootstrapPath}). Cloud sessions must not set these
 * — the vendor hosts WDA.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "ios.wda")
public class IosWdaProperties {

    private String agentPath =
            "/Applications/FireFlinkClient.app/Contents/Resources/flinko-client/exec/appium-webdriveragent/WebDriverAgent.xcodeproj";
    private String bootstrapPath =
            "/Applications/FireFlinkClient.app/Contents/Resources/flinko-client/exec/appium-webdriveragent";
    private boolean showXcodeLog = false;
    private String xcodeOrgId;
    private String xcodeSigningId;
    private String updatedWdaBundleId;
}
