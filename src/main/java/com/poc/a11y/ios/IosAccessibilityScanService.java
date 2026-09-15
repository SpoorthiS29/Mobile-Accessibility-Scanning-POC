package com.poc.a11y.ios;

import com.poc.a11y.ios.dto.IosAuditRawOutput;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import io.appium.java_client.ios.IOSDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * iOS accessibility scan: Appium XCUITest launches (or attaches), then
 * {@code mobile: performAccessibilityAudit} runs inside that session.
 * The session is not dropped before the audit (unlike Android ATF).
 */
@Service
public class IosAccessibilityScanService {

    private static final Logger log = LoggerFactory.getLogger(IosAccessibilityScanService.class);

    private final IosDriverManager iosDriverManager;
    private final IosAccessibilityAuditor auditor;
    private final IosAuditResultMapper mapper;

    public IosAccessibilityScanService(IosDriverManager iosDriverManager,
                                       IosAccessibilityAuditor auditor,
                                       IosAuditResultMapper mapper) {
        this.iosDriverManager = iosDriverManager;
        this.auditor = auditor;
        this.mapper = mapper;
    }

    public ScanResult scan(ScanRequest request) {
        IOSDriver driver = null;
        try {
            driver = acquireDriver(request);
            IosAuditRawOutput raw = auditor.run(driver, request);
            List<Issue> issues = mapper.toIssues(raw);
            return new ScanResult(
                    request.getPlatformName(),
                    request.getDeviceName(),
                    issues.size(),
                    issues
            );
        } finally {
            if (request.isCloseApp() && driver != null) {
                iosDriverManager.terminateApp(driver, request.resolveBundleId());
                iosDriverManager.closeSession(driver);
            }
        }
    }

    private IOSDriver acquireDriver(ScanRequest request) {
        IOSDriver existing = request.getExistingIosDriver();
        if (existing != null) {
            log.info("Using IOSDriver already on the request — not launching the app");
            return existing;
        }
        if (request.getAppiumSessionId() != null && !request.getAppiumSessionId().isBlank()) {
            log.info("Attaching to existing Appium session {} — not launching the app",
                    request.getAppiumSessionId());
            return iosDriverManager.attach(request);
        }
        log.info("No existing driver/session — launching the app via Appium XCUITest");
        return iosDriverManager.start(request);
    }
}
