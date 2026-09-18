package com.poc.a11y.atf;

import com.poc.a11y.atf.android.AndroidScanStrategy;
import com.poc.a11y.cloud.CloudPlatformAdapter;
import com.poc.a11y.cloud.CloudPlatformRegistry;
import com.poc.a11y.model.ScanRequest;
import com.poc.a11y.model.ScanResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes Android ATF to the matching device-control strategy (local adb vs
 * cloud Appium). iOS is handled separately by {@code IosAccessibilityScanService}.
 */
@Service
public class AtfMobileScanService {

    private final CloudPlatformRegistry cloudPlatformRegistry;
    private final List<AndroidScanStrategy> androidScanStrategies;

    public AtfMobileScanService(CloudPlatformRegistry cloudPlatformRegistry,
                                List<AndroidScanStrategy> androidScanStrategies) {
        this.cloudPlatformRegistry = cloudPlatformRegistry;
        this.androidScanStrategies = androidScanStrategies;
    }

    public ScanResult scanCurrentScreenWithRealAtf(ScanRequest request) {
        CloudPlatformAdapter adapter = cloudPlatformRegistry.resolve(request);
        adapter.validate(request);
        return androidScanStrategies.stream()
                .filter(strategy -> strategy.supports(adapter))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No Android ATF strategy for " + adapter.platform()
                                + ". Add an AndroidScanStrategy @Component."))
                .scan(request, adapter);
    }
}
