package com.poc.a11y.atf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.a11y.model.ScanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Sauce Labs REST helpers used only by the virtual-device path:
 * region URLs and App Storage uploads so Sauce Appium can install APKs.
 */
@Component
public class SauceLabsClient {

    private static final Logger log = LoggerFactory.getLogger(SauceLabsClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public String apiBaseUrl(ScanRequest request) {
        return "https://api." + region(request) + ".saucelabs.com";
    }

    public URL onDemandUrl(ScanRequest request) {
        String raw = "https://ondemand." + region(request) + ".saucelabs.com:443/wd/hub";
        try {
            return new URL(raw);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Sauce Labs on-demand URL: " + raw, e);
        }
    }

    /**
     * Looks up a Sauce App Storage file by content hash, not filename.
     * The same remote name can be many different APKs.
     */
    public String findAppInStorage(ScanRequest request, String remoteName, String sha256) {
        String url = apiBaseUrl(request)
                + "/v1/storage/files?sha256="
                + sha256
                + "&per_page=1"
                + "&icon_repr=hash";

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", basicAuth(request))
                .GET()
                .build();

        log.info("Checking Sauce App Storage for {} (sha256={})", remoteName, sha256);

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to check Sauce App Storage for " + remoteName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Sauce App Storage lookup interrupted for " + remoteName, e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Sauce App Storage lookup failed (HTTP "
                            + response.statusCode() + "): "
                            + response.body());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());

            if (root.isArray()) {
                return findStorageId(root, remoteName, sha256);
            }

            JsonNode items = root.get("items");

            if (items != null && items.isArray()) {
                return findStorageId(items, remoteName, sha256);
            }

            return null;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not parse Sauce App Storage response: "
                            + response.body(), e);
        }
    }

    private String findStorageId(JsonNode items, String remoteName, String sha256) {
        for (JsonNode item : items) {
            String storedHash = item.path("sha256").asText(null);
            if (storedHash != null && !storedHash.isBlank()
                    && !storedHash.equalsIgnoreCase(sha256)) {
                continue;
            }

            String id = item.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                String storageRef = "storage:" + id;
                log.info(
                        "Found existing Sauce App Storage file: {} -> {} (sha256={})",
                        remoteName,
                        storageRef,
                        sha256
                );
                return storageRef;
            }
        }

        log.info(
                "No existing Sauce App Storage file found for {} (sha256={})",
                remoteName,
                sha256
        );
        return null;
    }

    /**
     * Uploads a local APK to Sauce App Storage and returns a
     * {@code storage:&lt;fileId&gt;} reference for Appium capabilities / installApp.
     */
    public String uploadApp(ScanRequest request, Path apk, String remoteName) {

        if (apk == null || !Files.isRegularFile(apk)) {
            throw new IllegalArgumentException("APK does not exist: " + apk);
        }

        // Reuse only the same bytes. Filename alone can be an older build.
        String sha256 = sha256Hex(apk);
        String existingStorageRef = findAppInStorage(request, remoteName, sha256);

        if (existingStorageRef != null) {
            log.info(
                    "Reusing existing Sauce App Storage file: {} -> {} (sha256={})",
                    remoteName,
                    existingStorageRef,
                    sha256
            );

            return existingStorageRef;
        }

        log.info(
                "File {} (sha256={}) not found in Sauce App Storage. Uploading...",
                remoteName,
                sha256
        );

        String boundary = "----AtfSauceBoundary" + System.currentTimeMillis();

        byte[] body;

        try {
            body = multipartBody(boundary, apk, remoteName);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read APK for Sauce upload: " + apk,
                    e
            );
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl(request) + "/v1/storage/upload"))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", basicAuth(request))
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary
                )
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        log.info(
                "Uploading {} ({} bytes) to Sauce App Storage as {}",
                apk.getFileName(),
                body.length,
                remoteName
        );

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Sauce App Storage upload failed for " + remoteName,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Sauce App Storage upload interrupted for " + remoteName,
                    e
            );
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Sauce App Storage upload failed (HTTP "
                            + response.statusCode()
                            + "): "
                            + response.body()
            );
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());

            String id = text(root, "item", "id");

            if (id == null) {
                id = text(root, "id");
            }

            if (id == null || id.isBlank()) {
                throw new IllegalStateException(
                        "Sauce upload response had no file id: "
                                + response.body()
                );
            }

            String storageRef = "storage:" + id;

            log.info(
                    "Sauce App Storage uploaded {} -> {}",
                    remoteName,
                    storageRef
            );

            return storageRef;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not parse Sauce upload response: "
                            + response.body(),
                    e
            );
        }
    }

    private static String region(ScanRequest request) {
        String region = request.resolveCloudRegion();
        if (region == null || region.isBlank()) {
            return "eu-central-1";
        }
        return region.trim().toLowerCase();
    }

    private static String basicAuth(ScanRequest request) {
        String username = request.resolveCloudUsername();
        String accessKey = request.resolveCloudAccessKey();
        if (username == null || username.isBlank() || accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("cloudUsername and cloudAccessKey are required for Sauce Labs scans");
        }
        String token = username.trim() + ":" + accessKey.trim();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] multipartBody(String boundary, Path apk, String remoteName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"name\"" + crlf + crlf
                + remoteName + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"payload\"; filename=\""
                + remoteName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/vnd.android.package-archive" + crlf + crlf)
                .getBytes(StandardCharsets.UTF_8));
        Files.copy(apk, out);
        out.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * Streams the APK through SHA-256 so the lookup does not load the file
     * into a second heap copy. Same cost as one sequential disk read.
     */
    private static String sha256Hex(Path apk) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(apk)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    digest.update(buf, 0, n);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash APK for Sauce lookup: " + apk, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String text(JsonNode root, String... path) {
        JsonNode node = root;
        for (String key : path) {
            if (node == null) {
                return null;
            }
            node = node.get(key);
        }
        return node == null || node.isNull() ? null : node.asText();
    }
}
