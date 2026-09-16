package com.poc.a11y.atf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.a11y.atf.dto.AtfCheckResultDto;
import com.poc.a11y.atf.dto.AtfScanOutputDto;
import com.poc.a11y.model.ScanRequest;
import io.appium.java_client.android.AndroidDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs on-device ATF against whatever is already in the foreground.
 * Does not launch or close the app — UiAutomator restart and closeApp
 * are handled by {@link AtfMobileScanService} after the scan.
 */
@Component
public class AtfHarnessRunner {

    private static final Logger log = LoggerFactory.getLogger(AtfHarnessRunner.class);

    private final AtfProperties properties;
    private final AdbCommandExecutor adb;
    private final SauceLabsClient sauceLabsClient;
    private final AppiumDriverManager appiumDriverManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AtfHarnessRunner(AtfProperties properties,
                            AdbCommandExecutor adb,
                            SauceLabsClient sauceLabsClient,
                            AppiumDriverManager appiumDriverManager) {
        this.properties = properties;
        this.adb = adb;
        this.sauceLabsClient = sauceLabsClient;
        this.appiumDriverManager = appiumDriverManager;
    }

    public AtfScanOutputDto runScan(ScanRequest request) {
        String deviceSerial = request.getDeviceName();
        int timeout = properties.getAmInstrumentTimeoutSeconds();

        /* * 1. Make sure the ATF harness is installed. */
        ensureHarnessInstalled(deviceSerial, timeout);

        adb.run(deviceSerial, List.of("shell", "rm", "-f", properties.getDeviceResultPath()), timeout);
        adb.run(deviceSerial, List.of("shell", "rm", "-rf", properties.getDeviceShotsPath()), timeout);

        try {
            AdbCommandExecutor.Result instrumentResult =
                    adb.run(deviceSerial, buildInstrumentArgs(request), timeout);

            log.debug("am instrument output:\n{}", instrumentResult.output());

            if (!instrumentResult.isSuccess()) {
                throw new IllegalStateException(
                        "Real-ATF harness instrumentation failed (exit=" + instrumentResult.exitCode()
                                + "). An Appium UiAutomator2 session must already be quit. Output:\n"
                                + instrumentResult.output());
            }
            assertInstrumentSucceeded(instrumentResult.output(), deviceSerial);
            AtfScanOutputDto scanResult = pullAndParse(deviceSerial, timeout);
            String appName = safeAppName(request.getAppName());
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path localShots = pullShots(deviceSerial, timeout, appName, timestamp);
            remapScreenshotPaths(scanResult, localShots);
            saveScanResults(scanResult, appName, timestamp);
            return scanResult;
        } finally {
            adb.run(deviceSerial, List.of("shell", "rm", "-f", properties.getDeviceResultPath()), timeout);
            adb.run(deviceSerial, List.of("shell", "rm", "-rf", properties.getDeviceShotsPath()), timeout);
        }
    }

    /**
     * Same scan as {@link #runScan} (install harness, instrument, save JSON +
     * shots locally) but over a live Sauce Labs Appium session instead of adb.
     * Does not launch the app under test — that must already be in the
     * foreground from {@link AppiumDriverManager#startOnVirtualDevice}.
     */
    public AtfScanOutputDto runScanOnVirtualDevice(ScanRequest request, AndroidDriver driver) {
        if (driver == null) {
            throw new IllegalArgumentException("AndroidDriver is required for a virtual-device ATF scan");
        }
        ensureHarnessInstalledOnVirtualDevice(request, driver);
        try {
            executeShell(driver, "rm", List.of("-f", properties.getDeviceResultPath()));
            executeShell(driver, "rm", List.of("-rf", properties.getDeviceShotsPath()));
        } catch (RuntimeException e) {
            log.warn("Could not clear previous ATF files on virtual device: {}", e.getMessage());
        }

        appiumDriverManager.releaseUiAutomation(driver);
        AppiumDriverManager.sleepQuietly(2);

        String instrumentOutput = runInstrumentOnVirtualDevice(request, driver);
        log.debug("am instrument output:\n{}", instrumentOutput);
        assertInstrumentSucceeded(instrumentOutput, request.getDeviceName());

        AtfScanOutputDto scanResult = pullAndParseFromDriver(driver);
        String appName = safeAppName(request.getAppName());
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path localShots = pullShotsFromDriver(driver, appName, timestamp);
        remapScreenshotPaths(scanResult, localShots);
        saveScanResults(scanResult, appName, timestamp);
        return scanResult;
    }

    private void saveScanResults(
            AtfScanOutputDto scanResult,
            String appName,
            String timestamp) {

        try {
            Path resultsDir = Paths.get("atf-results");
            Files.createDirectories(resultsDir);

            Path resultFile = resultsDir.resolve(
                    "atf-scan-" + appName + "-" + timestamp + ".json"
            );

            String json = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(scanResult);

            Files.writeString(
                    resultFile,
                    json,
                    StandardCharsets.UTF_8
            );

            log.info(
                    "ATF scan results saved to: {}",
                    resultFile.toAbsolutePath()
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to save ATF scan results locally", e);
        }
    }

    private List<String> buildInstrumentArgs(ScanRequest request) {
        List<String> args = new ArrayList<>();
        args.add("shell");
        args.add("am");
        args.add("instrument");
        args.add("-w");
        args.add("-r");
        addInstrumentExtras(args, request);
        args.add(instrumentTarget());
        return args;
    }

    private void addInstrumentExtras(List<String> args, ScanRequest request) {
        args.add("-e");
        args.add("class");
        args.add(properties.getHarnessTestClass());
        args.add("-e");
        args.add("attachOnly");
        args.add("true");
        args.add("-e");
        args.add("scrollToEnd");
        args.add(String.valueOf(request.isScroll()));
        args.add("-e");
        args.add("maxScrolls");
        args.add(String.valueOf(request.getMaxScrolls()));
    }

    private String instrumentTarget() {
        return properties.getHarnessTestPackage() + "/" + properties.getHarnessInstrumentationRunner();
    }

    private AtfScanOutputDto pullAndParse(String deviceSerial, int timeout) {
        Path localFile = null;
        try {
            localFile = Files.createTempFile("atf-result-", ".json");
            AdbCommandExecutor.Result pullResult = adb.run(deviceSerial,
                    List.of("pull", properties.getDeviceResultPath(), localFile.toString()), timeout);
            if (!pullResult.isSuccess()) {
                throw new IllegalStateException(
                        "adb pull of ATF result JSON failed:\n" + pullResult.output());
            }
            String json = Files.readString(localFile);
            return objectMapper.readValue(json, AtfScanOutputDto.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ATF result JSON pulled from device", e);
        } finally {
            if (localFile != null) {
                try {
                    Files.deleteIfExists(localFile);
                } catch (IOException ignored) {
                    // best-effort local temp-file cleanup
                }
            }
        }
    }

    private Path pullShots(String deviceSerial, int timeout, String appName, String timestamp) {
        Path shotsDir = Paths.get("atf-results")
                .resolve("atf-scan-" + appName + "-" + timestamp + "-shots");
        try {
            Files.createDirectories(shotsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create local shots directory " + shotsDir, e);
        }
        AdbCommandExecutor.Result pullResult = adb.run(deviceSerial,
                List.of("pull", properties.getDeviceShotsPath(), shotsDir.toString()), timeout);
        if (!pullResult.isSuccess()) {
            log.warn("adb pull of ATF crops failed (scan continues without screenshots):\n{}",
                    pullResult.output());
        } else {
            log.info("ATF element crops pulled to: {}", shotsDir.toAbsolutePath());
        }
        return shotsDir;
    }

    private void remapScreenshotPaths(AtfScanOutputDto scanResult, Path localShots) {
        if (scanResult == null || scanResult.getResults() == null || localShots == null) {
            return;
        }
        for (AtfCheckResultDto result : scanResult.getResults()) {
            String relative = result.getScreenshotFile();
            if (relative == null || relative.isBlank()) {
                continue;
            }
            String fileName = Path.of(relative).getFileName().toString();
            Path local = localShots.resolve(fileName);
            if (!Files.exists(local)) {
                local = localShots.resolve("shots").resolve(fileName);
            }
            if (Files.exists(local)) {
                result.setScreenshotFile(local.toAbsolutePath().toString());
            }
        }
    }

    private static String safeAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            return "app";
        }
        return appName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void ensureHarnessInstalled(String deviceSerial, int timeout) {
        installApkIfNeeded(
                deviceSerial,
                properties.getHarnessPackage(),
                properties.getHarnessApkPath(),
                "ATF harness APK",
                timeout);
        installApkIfNeeded(
                deviceSerial,
                properties.getHarnessTestPackage(),
                properties.getHarnessTestApkPath(),
                "ATF instrumentation APK",
                timeout);
    }

    private void installApkIfNeeded(
            String deviceSerial,
            String packageName,
            String apkPath,
            String description,
            int timeout) {
        Path apk = resolveConfiguredApk(apkPath, description);
        PackageIdentity desired = readApkIdentity(apk);
        PackageIdentity installed = readInstalledIdentity(deviceSerial, packageName, timeout);
        long apkLastModified = apkLastModifiedMs(apk);
        if (!PackageIdentity.needsInstall(installed, desired, apkLastModified)) {
            log.info("{} is already installed with {} — skipping install",
                    description, desired == null ? "unknown version" : desired.displayVersion());
            return;
        }
        log.info("Installing {} {} (device had {})",
                description,
                desired == null ? "unknown version" : desired.displayVersion(),
                installed == null ? "nothing / unknown version" : installed.displayVersion());
        installApk(deviceSerial, apk, description, timeout);
        if (!isPackageInstalled(deviceSerial, packageName, timeout)) {
            throw new IllegalStateException(description + " installation failed. Package not found: " + packageName);
        }
    }

    private boolean isPackageInstalled(String deviceSerial, String packageName, int timeout) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalStateException("Package name is not configured");
        }
        AdbCommandExecutor.Result result =
                adb.run(deviceSerial, List.of("shell", "pm", "path", packageName), timeout);
        return result.isSuccess() && result.output() != null && result.output().contains("package:");
    }

    private void installApk(String deviceSerial, Path apk, String description, int timeout) {
        log.info("Installing {}: {}", description, apk);
        AdbCommandExecutor.Result result =
                adb.run(deviceSerial, List.of("install", "-r", "-d", apk.toString()), timeout);
        if (!result.isSuccess() || !result.output().contains("Success")) {
            throw new IllegalStateException(
                    "Failed to install " + description + " on " + deviceSerial
                            + "\nAPK: " + apk + "\nADB output:\n" + result.output());
        }
        log.info("{} installed successfully", description);
    }

    private void ensureHarnessInstalledOnVirtualDevice(ScanRequest request, AndroidDriver driver) {
        installVirtualApkIfNeeded(
                request,
                driver,
                properties.getHarnessPackage(),
                properties.getHarnessApkPath(),
                "ATF harness APK");
        installVirtualApkIfNeeded(
                request,
                driver,
                properties.getHarnessTestPackage(),
                properties.getHarnessTestApkPath(),
                "ATF instrumentation APK");
    }

    private void installVirtualApkIfNeeded(
            ScanRequest request,
            AndroidDriver driver,
            String packageName,
            String apkPath,
            String description) {
        Path apk = resolveConfiguredApk(apkPath, description);
        PackageIdentity desired = readApkIdentity(apk);
        PackageIdentity installed = PackageIdentity.fromDumpsys(
                executeShell(driver, "dumpsys", List.of("package", packageName)));
        long apkLastModified = apkLastModifiedMs(apk);
        if (!PackageIdentity.needsInstall(installed, desired, apkLastModified)) {
            log.info("{} is already installed with {} on virtual device — skipping install",
                    description, desired == null ? "unknown version" : desired.displayVersion());
            return;
        }
        log.info("Installing {} {} on Sauce virtual device (device had {})",
                description,
                desired == null ? "unknown version" : desired.displayVersion(),
                installed == null ? "nothing / unknown version" : installed.displayVersion());
        String storageApp = sauceLabsClient.uploadApp(request, apk, apk.getFileName().toString());
//        driver.executeScript("mobile: installApp", Map.of("appPath", storageApp));

        driver.installApp(storageApp);
//        driver.executeScript("mobile: installMultipleApks", Map.of(
//                "apks", List.of(storageApp),
//                "options", Map.of(
//                        "replace", true
//                )
//        ));
    }

    private PackageIdentity readApkIdentity(Path apk) {
        try {
            return ApkManifestReader.read(apk);
        } catch (RuntimeException e) {
            log.warn("Could not read version from {}: {}", apk, e.getMessage());
            return null;
        }
    }

    private static long apkLastModifiedMs(Path apk) {
        try {
            return Files.getLastModifiedTime(apk).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private PackageIdentity readInstalledIdentity(String deviceSerial, String packageName, int timeout) {
        AdbCommandExecutor.Result result =
                adb.run(deviceSerial, List.of("shell", "dumpsys", "package", packageName), timeout);
        if (!result.isSuccess()) {
            return null;
        }
        return PackageIdentity.fromDumpsys(result.output());
    }

    private Path resolveConfiguredApk(String apkPath, String description) {
        if (apkPath == null || apkPath.isBlank()) {
            throw new IllegalStateException(description + " path is not configured");
        }
        Path apk = Paths.get(apkPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(apk)) {
            throw new IllegalStateException(description + " does not exist: " + apk);
        }
        return apk;
    }

    /**
     * Frees the UiAutomator2 slot in the same adb-shell invocation, then runs
     * the existing ATF instrumentation. Appium's HTTP session stays open so
     * results can be pulled afterward (Sauce restarts UiAutomator2 on the next
     * command). The app under test is not force-stopped.
     */
//    private String runInstrumentOnVirtualDevice(ScanRequest request, AndroidDriver driver) {
//        List<String> args = new ArrayList<>();
//        args.add("instrument");
//        args.add("-w");
//        args.add("-r");
//        addInstrumentExtras(args, request);
//        args.add(instrumentTarget());
//        log.info("Running ATF instrumentation: am {}", String.join(" ", args));
//        return executeShell(driver, "am", args);
//    }

    private String runInstrumentOnVirtualDevice(ScanRequest request, AndroidDriver driver) {
        List<String> args = new ArrayList<>();
        args.add("instrument");
        args.add("-w");
        args.add("-r");
        addInstrumentExtras(args, request);
        args.add(instrumentTarget());
        log.info("Running ATF instrumentation: am {}", String.join(" ", args));

        long timeoutMs = (properties.getAmInstrumentTimeoutSeconds() + 30) * 1000L;
        return executeShell(driver, "am", args, timeoutMs);
    }

//    private String executeShell(AndroidDriver driver, String command, List<String> args) {
//        try {
//            Object raw = driver.executeScript("mobile: shell", Map.of(
//                    "command", command,
//                    "args", args
//            ));
//            return raw == null ? "" : String.valueOf(raw);
//        } catch (RuntimeException e) {
//            throw new IllegalStateException(
//                    "Sauce Labs virtual devices must allow Appium mobile: shell so the ATF harness "
//                            + "can run after the app is launched (same as adb shell on a physical device). "
//                            + command + " failed: " + e.getMessage(), e);
//        }
//    }

    private String executeShell(AndroidDriver driver, String command, List<String> args) {
        return executeShell(driver, command, args, 20000); // keep existing default for short calls
    }

    private String executeShell(AndroidDriver driver, String command, List<String> args, long timeoutMs) {
        try {
            Object raw = driver.executeScript("mobile: shell", Map.of(
                    "command", command,
                    "args", args,
                    "timeout", timeoutMs
            ));
            return raw == null ? "" : String.valueOf(raw);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Sauce Labs virtual devices must allow Appium mobile: shell so the ATF harness "
                            + "can run after the app is launched (same as adb shell on a physical device). "
                            + command + " failed: " + e.getMessage(), e);
        }
    }

    private void assertInstrumentSucceeded(String output, String deviceName) {
        if (output == null) {
            return;
        }
        if (output.contains("INSTRUMENTATION_FAILED")) {
            throw new IllegalStateException(
                    "Real-ATF harness instrumentation failed. Is atf-harness installed on "
                            + deviceName + "? Output:\n" + output);
        }
        if (output.contains("FAILURES!!!")
                || output.contains("Error in runAtfScanAndDumpJson")) {
            throw new IllegalStateException(
                    "Real-ATF harness test failed (no result JSON will exist). Output:\n" + output);
        }
    }

    private AtfScanOutputDto pullAndParseFromDriver(AndroidDriver driver) {
        byte[] jsonBytes = pullFileWithRetry(driver, properties.getDeviceResultPath());
        try {
            return objectMapper.readValue(jsonBytes, AtfScanOutputDto.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse ATF result JSON pulled from virtual device", e);
        }
    }

    private Path pullShotsFromDriver(AndroidDriver driver, String appName, String timestamp) {
        Path shotsDir = Paths.get("atf-results")
                .resolve("atf-scan-" + appName + "-" + timestamp + "-shots");
        try {
            Files.createDirectories(shotsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create local shots directory " + shotsDir, e);
        }
        try {
            byte[] zipBytes = pullFolderWithRetry(driver, properties.getDeviceShotsPath());
            unzipTo(zipBytes, shotsDir);
            log.info("ATF element crops pulled to: {}", shotsDir.toAbsolutePath());
        } catch (RuntimeException | IOException e) {
            log.warn("Pull of ATF crops failed (scan continues without screenshots): {}", e.getMessage());
        }
        return shotsDir;
    }

    private byte[] pullFileWithRetry(AndroidDriver driver, String remotePath) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                return driver.pullFile(remotePath);
            } catch (RuntimeException e) {
                last = e;
                log.warn("pullFile {} attempt {} failed: {}", remotePath, attempt, e.getMessage());
                AppiumDriverManager.sleepQuietly(5);
            }
        }
        throw new IllegalStateException("adb/Appium pull of ATF result JSON failed: " + remotePath, last);
    }

    private byte[] pullFolderWithRetry(AndroidDriver driver, String remotePath) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                return driver.pullFolder(remotePath);
            } catch (RuntimeException e) {
                last = e;
                log.warn("pullFolder {} attempt {} failed: {}", remotePath, attempt, e.getMessage());
                AppiumDriverManager.sleepQuietly(3);
            }
        }
        throw new IllegalStateException("Could not pull ATF shots folder: " + remotePath, last);
    }

    private static void unzipTo(byte[] zipBytes, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = Path.of(entry.getName()).getFileName().toString();
                Path dest = targetDir.resolve(name);
                Files.copy(zis, dest);
                zis.closeEntry();
            }
        }
    }

}
