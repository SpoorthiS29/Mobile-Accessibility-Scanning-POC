package com.poc.a11y.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.a11y.model.ScanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * BrowserStack App Automate storage. Mirrors Optimize's
 * {@code BROWSER_STACK_UPLOAD_FILE_URL} / {@code bs://} app prefix.
 */
@Component
public class BrowserStackClient {

    private static final Logger log = LoggerFactory.getLogger(BrowserStackClient.class);
    private static final String UPLOAD_URL = "https://api-cloud.browserstack.com/app-automate/upload";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public String uploadApp(ScanRequest request, Path localFile, String remoteName) {
        log.info("Uploading {} to BrowserStack App Automate as {}", localFile.getFileName(), remoteName);
        String body = CloudHttpSupport.postMultipart(
                httpClient,
                UPLOAD_URL,
                CloudHttpSupport.basicAuth(request.resolveCloudUsername(), request.resolveCloudAccessKey()),
                "file",
                localFile,
                remoteName,
                Map.of(),
                Duration.ofMinutes(5));
        try {
            JsonNode root = objectMapper.readTree(body);
            String appUrl = text(root, "app_url");
            if (appUrl == null || appUrl.isBlank()) {
                appUrl = text(root, "appUrl");
            }
            if (appUrl == null || appUrl.isBlank()) {
                throw new IllegalStateException("BrowserStack upload response had no app_url: " + body);
            }
            log.info("BrowserStack app uploaded {} -> {}", remoteName, appUrl);
            return appUrl;
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse BrowserStack upload response: " + body, e);
        }
    }

    private static String text(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }
}
