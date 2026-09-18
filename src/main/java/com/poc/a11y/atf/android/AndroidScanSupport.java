package com.poc.a11y.atf.android;

import com.poc.a11y.atf.AppiumDriverManager;
import com.poc.a11y.atf.AtfResultMapper;
import com.poc.a11y.atf.dto.AtfScanOutputDto;
import com.poc.a11y.model.Issue;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AndroidScanSupport {

    private static final Logger log = LoggerFactory.getLogger(AndroidScanSupport.class);

    private final AtfResultMapper resultMapper;

    public AndroidScanSupport(AtfResultMapper resultMapper) {
        this.resultMapper = resultMapper;
    }

    public ScanResult toScanResult(ScanRequest request, AtfScanOutputDto rawResult) {
        List<Issue> issues = resultMapper.toIssues(rawResult);
        int elementsScanned = rawResult.getResultCount();
        return new ScanResult(
                request.getPlatformName(),
                request.getDeviceName(),
                elementsScanned,
                issues
        );
    }

    public void confirmLaunch(ScanRequest request, AndroidDriver driver) {
        AppiumDriverManager.sleepQuietly(request.getPostLaunchWaitSeconds());
        if (driver == null) {
            return;
        }
        try {
            log.info("App in foreground: package={} activity={}",
                    driver.getCurrentPackage(), driver.currentActivity());
        } catch (Exception e) {
            log.warn("Could not confirm foreground app: {}", e.getMessage());
        }
        enrichPackageFromSession(request, driver);
    }

    public void enrichPackageFromSession(ScanRequest request, AndroidDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            if (request.getAppPackage() == null || request.getAppPackage().isBlank()) {
                request.setAppPackage(driver.getCurrentPackage());
            }
            if (request.getAppActivity() == null || request.getAppActivity().isBlank()) {
                request.setAppActivity(driver.currentActivity());
            }
        } catch (Exception e) {
            log.warn("Could not read package/activity from Appium: {}", e.getMessage());
        }
    }
}
