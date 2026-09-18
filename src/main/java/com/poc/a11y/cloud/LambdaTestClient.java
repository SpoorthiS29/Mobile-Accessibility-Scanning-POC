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
 * LambdaTest real-device app storage. Mirrors Optimize's
 * {@code LAMBDA_TEST_UPLOAD_FILE_URL} / {@code lt://} app prefix.
 */
@Component
public class LambdaTestClient {

    private static final Logger log = LoggerFactory.getLogger(LambdaTestClient.class);
    private static final String UPLOAD_URL = "https://manual-api.lambdatest.com/app/upload/realDevice";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public String uploadApp(ScanRequest request, Path localFile, String remoteName) {
        log.info("Uploading {} to LambdaTest as {}", localFile.getFileName(), remoteName);
        String body = CloudHttpSupport.postMultipart(
                httpClient,
                UPLOAD_URL,
                CloudHttpSupport.basicAuth(request.resolveCloudUsername(), request.resolveCloudAccessKey()),
                "appFile",
                localFile,
                remoteName,
                Map.of("name", remoteName),
                Duration.ofMinutes(5));
        try {
            JsonNode root = objectMapper.readTree(body);
            String appUrl = text(root, "app_url");
            if (appUrl == null || appUrl.isBlank()) {
                appUrl = text(root, "app_id");
                if (appUrl != null && !appUrl.isBlank() && !appUrl.startsWith("lt://")) {
                    appUrl = "lt://" + appUrl;
                }
            }
            if (appUrl == null || appUrl.isBlank()) {
                throw new IllegalStateException("LambdaTest upload response had no app_url: " + body);
            }
            log.info("LambdaTest app uploaded {} -> {}", remoteName, appUrl);
            return appUrl;
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse LambdaTest upload response: " + body, e);
        }
    }

    private static String text(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }
}
