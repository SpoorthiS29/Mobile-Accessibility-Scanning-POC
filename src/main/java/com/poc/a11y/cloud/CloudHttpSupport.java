package com.poc.a11y.cloud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

public final class CloudHttpSupport {

    private CloudHttpSupport() {
    }

    public static String basicAuth(String username, String accessKey) {
        if (username == null || username.isBlank() || accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("cloudUsername and cloudAccessKey are required");
        }
        String token = username.trim() + ":" + accessKey.trim();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    public static URL toUrl(String raw) {
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Appium server URL: " + raw, e);
        }
    }

    public static String postMultipart(
            HttpClient httpClient,
            String url,
            String authorization,
            String fileField,
            Path file,
            String remoteName,
            Map<String, String> extraFields,
            Duration timeout) {
        String boundary = "----A11yCloudBoundary" + System.currentTimeMillis();
        byte[] body;
        try {
            body = multipartBody(boundary, fileField, file, remoteName, extraFields);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read app for cloud upload: " + file, e);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cloud app upload failed for " + remoteName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cloud app upload interrupted for " + remoteName, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Cloud app upload failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static byte[] multipartBody(
            String boundary,
            String fileField,
            Path file,
            String remoteName,
            Map<String, String> extraFields) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";
        if (extraFields != null) {
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\""
                        + crlf + crlf + entry.getValue() + crlf).getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                + remoteName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/octet-stream" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        Files.copy(file, out);
        out.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
