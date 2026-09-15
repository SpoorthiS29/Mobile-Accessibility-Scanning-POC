package com.poc.a11y.atf;

import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

    /**
     * Real-ATF scan of the current screen. Pass {@code "scroll": true} to
     * scroll and scan successive viewports; otherwise only the current
     * viewport is scanned.
     * <p>
     * Physical device ({@code virtualDevice} omitted/false): if
     * {@code existingDriver} or {@code appiumSessionId} is set, the app is not
     * launched. Otherwise Appium opens it once. The Appium session is quit
     * before ATF; the app is left running.
     * <p>
     * Sauce Labs virtual device ({@code virtualDevice: true}): credentials and
     * {@code appPath} come from the payload. Appium installs and launches the
     * app under test first; the ATF harness then only scans, scrolls, and
     * writes issues + screenshots.
     */
@RestController
@RequestMapping("/api/scan")
public class AtfScanController {

    private final AtfMobileScanService atfMobileScanService;

    public AtfScanController(AtfMobileScanService atfMobileScanService) {
        this.atfMobileScanService = atfMobileScanService;
    }

    @PostMapping("/mobile/atf")
    public ScanResult scanMobileWithAtf(@Valid @RequestBody ScanRequest request) {
        return atfMobileScanService.scanCurrentScreenWithRealAtf(request);
    }
}
