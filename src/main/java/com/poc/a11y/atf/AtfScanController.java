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
     * viewport is scanned. If {@code existingDriver} or {@code appiumSessionId}
     * is set, the app is not launched. Otherwise Appium opens it once (POC).
     * The Appium session is quit before ATF; the app is left running.
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
