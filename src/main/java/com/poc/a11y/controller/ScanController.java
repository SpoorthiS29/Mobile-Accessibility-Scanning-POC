package com.poc.a11y.controller;

import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import com.poc.a11y.service.MobileScanEngine;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final MobileScanEngine scanEngine;

    public ScanController(MobileScanEngine scanEngine) {
        this.scanEngine = scanEngine;
    }

    /**
     * Launches the app described in the request, waits for it to load, and
     * scans whatever screen is on top when the wait ends. For a multi-screen
     * POC, call this again after driving navigation from your own test code /
     * a follow-up endpoint - this engine intentionally scans one screen at a
     * time so it can be composed into a larger walk of the app.
     */
    @PostMapping("/mobile")
    public ScanResult scanMobile(@Valid @RequestBody ScanRequest request) {
        return scanEngine.scanCurrentScreen(request);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
