package com.poc.a11y.atf;

import com.poc.a11y.ios.IosAccessibilityScanService;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accessibility scan of the current screen. {@code platformName} selects
 * the engine: {@code iOS} uses XCUITest + XCUIAccessibilityAudit; anything
 * else uses Android ATF.
 * <p>
 * Pass {@code "scroll": true} to scroll and scan successive viewports;
 * otherwise only the current viewport is scanned. {@code closeApp: true}
 * terminates the app and quits the Appium session after the scan.
 * <p>
 * {@code cloudPlatform} selects the device host: {@code local} (default,
 * FireFlink Client / USB), {@code sauceLabs}, {@code browserStack}, or
 * {@code lambdaTest}. {@code virtualDevice: true} is still accepted as
 * Sauce Labs. Cloud payloads need {@code cloudUsername}, {@code cloudAccessKey},
 * {@code deviceName}, {@code platformVersion}, and usually {@code appPath}.
 * <p>
 * Local iOS sessions send FireFlink's signed {@code agentPath}/{@code bootstrapPath}
 * so Appium can build WDA. Cloud iOS omits those paths.
 */
@RestController
@RequestMapping("/api/scan")
public class AtfScanController {

    private final MobileAccessibilityScanFacade scanFacade;

    public AtfScanController(MobileAccessibilityScanFacade scanFacade) {
        this.scanFacade = scanFacade;
    }

    @PostMapping("/mobile/atf")
    public ScanResult scanMobileWithAtf(@Valid @RequestBody ScanRequest request) {
        return scanFacade.scan(request);
    }
}
