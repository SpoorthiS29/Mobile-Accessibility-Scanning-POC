package com.poc.a11y.atf;

import com.poc.a11y.ios.IosAccessibilityScanService;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes {@code POST /api/scan/mobile/atf} to Android ATF or iOS
 * XCUIAccessibilityAudit based on {@link ScanRequest#getPlatformName()}.
 */
@Service
public class MobileAccessibilityScanFacade {

    private static final Logger log = LoggerFactory.getLogger(MobileAccessibilityScanFacade.class);

    private final AtfMobileScanService androidScanService;
    private final IosAccessibilityScanService iosScanService;

    public MobileAccessibilityScanFacade(AtfMobileScanService androidScanService,
                                         IosAccessibilityScanService iosScanService) {
        this.androidScanService = androidScanService;
        this.iosScanService = iosScanService;
    }

    public ScanResult scan(ScanRequest request) {
        if (request.isIos()) {
            log.info("Routing accessibility scan to iOS XCUIAccessibilityAudit");
            return iosScanService.scan(request);
        }
        log.info("Routing accessibility scan to Android ATF");
        return androidScanService.scanCurrentScreenWithRealAtf(request);
    }
}
