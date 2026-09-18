package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LambdaTestCloudPlatformAdapter extends AbstractRemoteCloudPlatformAdapter {

    private static final String DEFAULT_HUB = "https://mobile-hub.lambdatest.com/wd/hub";

    private final LambdaTestClient lambdaTestClient;

    public LambdaTestCloudPlatformAdapter(LambdaTestClient lambdaTestClient) {
        this.lambdaTestClient = lambdaTestClient;
    }

    @Override
    public CloudPlatform platform() {
        return CloudPlatform.LAMBDATEST;
    }

    @Override
    public URL appiumHubUrl(ScanRequest request) {
        if (!CloudAppRefs.isLocalDefaultAppiumUrl(request.getAppiumServerUrl())) {
            return CloudHttpSupport.toUrl(request.getAppiumServerUrl());
        }
        return CloudHttpSupport.toUrl(DEFAULT_HUB);
    }

    @Override
    public Map<String, Object> vendorOptions(ScanRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("user", request.resolveCloudUsername());
        options.put("accessKey", request.resolveCloudAccessKey());
        options.put("build", sessionName(request));
        options.put("name", sessionName(request));
        options.put("isRealMobile", true);
        options.put("w3c", true);
        return options;
    }

    @Override
    public String vendorOptionsKey() {
        return "lt:options";
    }

    @Override
    public String uploadApp(ScanRequest request, Path localFile) {
        return lambdaTestClient.uploadApp(request, localFile, localFile.getFileName().toString());
    }
}
