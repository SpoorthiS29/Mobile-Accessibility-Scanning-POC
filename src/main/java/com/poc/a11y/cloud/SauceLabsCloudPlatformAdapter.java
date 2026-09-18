package com.poc.a11y.cloud;

import com.poc.a11y.atf.SauceLabsClient;
import com.poc.a11y.model.ScanRequest;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SauceLabsCloudPlatformAdapter extends AbstractRemoteCloudPlatformAdapter {

    private final SauceLabsClient sauceLabsClient;

    public SauceLabsCloudPlatformAdapter(SauceLabsClient sauceLabsClient) {
        this.sauceLabsClient = sauceLabsClient;
    }

    @Override
    public CloudPlatform platform() {
        return CloudPlatform.SAUCE_LABS;
    }

    @Override
    public URL appiumHubUrl(ScanRequest request) {
        if (!CloudAppRefs.isLocalDefaultAppiumUrl(request.getAppiumServerUrl())) {
            return CloudHttpSupport.toUrl(request.getAppiumServerUrl());
        }
        return sauceLabsClient.onDemandUrl(request);
    }

    @Override
    public Map<String, Object> vendorOptions(ScanRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("username", request.resolveCloudUsername());
        options.put("accessKey", request.resolveCloudAccessKey());
        options.put("name", sessionName(request));
        return options;
    }

    @Override
    public String vendorOptionsKey() {
        return "sauce:options";
    }

    @Override
    public String uploadApp(ScanRequest request, Path localFile) {
        return sauceLabsClient.uploadApp(request, localFile, localFile.getFileName().toString());
    }
}
