package com.poc.a11y.atf;

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
     * otherwise only the current viewport is scanned.
     * <p>
     * Android physical device ({@code virtualDevice} omitted/false): if
     * {@code existingDriver} or {@code appiumSessionId} is set, the app is not
     * launched. Otherwise Appium opens it once. The Appium session is quit
     * before ATF; the app is left running.
     * <p>
     * Android Sauce Labs virtual device ({@code virtualDevice: true}):
     * credentials and {@code appPath} come from the payload. Appium installs
     * and launches the app under test first; the ATF harness then only scans,
     * scrolls, and writes issues + screenshots.
     * <p>
     * iOS: Appium XCUITest launches or attaches, then
     * {@code mobile: performAccessibilityAudit} runs in that session. Raw
     * audit JSON and screenshots are stored under {@code ios-results/}.
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
