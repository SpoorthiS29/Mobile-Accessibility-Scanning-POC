package com.poc.a11y.cloud;

import com.poc.a11y.model.ScanRequest;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class CloudPlatformRegistry {

    private final Map<CloudPlatform, CloudPlatformAdapter> adapters = new EnumMap<>(CloudPlatform.class);

    public CloudPlatformRegistry(List<CloudPlatformAdapter> adapterList) {
        for (CloudPlatformAdapter adapter : adapterList) {
            CloudPlatformAdapter previous = adapters.put(adapter.platform(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate CloudPlatformAdapter for " + adapter.platform()
                                + ": " + previous.getClass().getName()
                                + " and " + adapter.getClass().getName());
            }
        }
    }

    public CloudPlatformAdapter resolve(ScanRequest request) {
        CloudPlatform platform = request.resolveCloudPlatform();
        CloudPlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            throw new IllegalStateException(
                    "No CloudPlatformAdapter registered for " + platform
                            + ". Add a @Component implementing CloudPlatformAdapter.");
        }
        return adapter;
    }
}
