package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BrowserStackCloudPlatformAdapter extends AbstractRemoteCloudPlatformAdapter {

    private static final String DEFAULT_HUB = "https://hub-cloud.browserstack.com/wd/hub";

    private final BrowserStackClient browserStackClient;

    public BrowserStackCloudPlatformAdapter(BrowserStackClient browserStackClient) {
        this.browserStackClient = browserStackClient;
    }

    @Override
    public CloudPlatform platform() {
        return CloudPlatform.BROWSERSTACK;
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
        options.put("userName", request.resolveCloudUsername());
        options.put("accessKey", request.resolveCloudAccessKey());
        options.put("sessionName", sessionName(request));
        return options;
    }

    @Override
    public String vendorOptionsKey() {
        return "bstack:options";
    }

    @Override
    public String uploadApp(ScanRequest request, Path localFile) {
        return browserStackClient.uploadApp(request, localFile, localFile.getFileName().toString());
    }
}
