package com.poc.a11y.atf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.a11y.atf.dto.AtfCheckResultDto;
import com.poc.a11y.atf.dto.AtfScanOutputDto;
import com.poc.a11y.model.ScanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs on-device ATF against whatever is already in the foreground.
 * Does not launch the app. Force-stops it only when {@code closeApp} is true.
 */
@Component
public class AtfHarnessRunner {

    private static final Logger log = LoggerFactory.getLogger(AtfHarnessRunner.class);

    private final AtfProperties properties;
    private final AdbCommandExecutor adb;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AtfHarnessRunner(AtfProperties properties, AdbCommandExecutor adb) {
        this.properties = properties;
        this.adb = adb;
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
            if (instrumentResult.output().contains("INSTRUMENTATION_FAILED")) {
                throw new IllegalStateException(
                        "Real-ATF harness instrumentation failed. Is atf-harness installed on "
                                + deviceSerial + "? Output:\n" + instrumentResult.output());
            }
            if (instrumentResult.output().contains("FAILURES!!!")
                    || instrumentResult.output().contains("Error in runAtfScanAndDumpJson")) {
                throw new IllegalStateException(
                        "Real-ATF harness test failed (no result JSON will exist). Output:\n"
                                + instrumentResult.output());
            }
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
            closeAppIfRequested(request, timeout);
        }
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
        String instrumentTarget = properties.getHarnessTestPackage() + "/"
                + properties.getHarnessInstrumentationRunner();
        List<String> args = new ArrayList<>();
        args.add("shell");
        args.add("am");
        args.add("instrument");
        args.add("-w");
        args.add("-r");
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
        args.add(String.valueOf(Math.max(1, request.getMaxScrolls())));
        args.add(instrumentTarget);
        return args;
    }

    private void closeAppIfRequested(ScanRequest request, int timeout) {
        if (!request.isCloseApp()) {
            return;
        }
        String appPackage = request.getAppPackage();
        if (appPackage == null || appPackage.isBlank()) {
            log.warn("closeApp=true but appPackage is empty — cannot force-stop");
            return;
        }
        log.info("closeApp=true — force-stopping {}", appPackage);
        adb.run(request.getDeviceName(), List.of("shell", "am", "force-stop", appPackage.trim()), timeout);
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

    /**
     * Checks whether the ATF harness package is installed. * * <p>Uses:</p> * * <pre> * adb -s &lt;device&gt; shell pm path &lt;package&gt; * </pre> * * <p>If pm path returns a package path, the application is installed.</p>
     */
    private boolean isHarnessInstalled(String deviceSerial, int timeout) {
        String harnessPackage = properties.getHarnessTestPackage();
        if (harnessPackage == null || harnessPackage.isBlank()) {
            throw new IllegalStateException("ATF harness package is not configured");
        }
        log.info("Checking whether ATF harness is installed: {}", harnessPackage);
        AdbCommandExecutor.Result result = adb.run(deviceSerial, List.of("shell", "pm", "path", harnessPackage), timeout);
        if (!result.isSuccess()) {
            log.warn("Could not check ATF harness installation. " + "adb output: {}", result.output());
            return false;
        }
        String output = result.output();
        boolean installed = output != null && output.contains("package:");
        if (installed) {
            log.info("ATF harness is already installed: {}", harnessPackage);
        } else {
            log.info("ATF harness is NOT installed: {}", harnessPackage);
        }
        return installed;
    }

    /**
     * Makes sure that the ATF harness APK is installed. * * <p>If already installed, nothing is done.</p> * * <p>If not installed, the configured APK is installed using:</p> * * <pre> * adb -s &lt;device&gt; install -r &lt;apk&gt; * </pre>
     */
    private void ensureHarnessInstalled(String deviceSerial, int timeout) {

        String harnessPackage = properties.getHarnessPackage();

        String testPackage = properties.getHarnessTestPackage();

        boolean harnessInstalled = isPackageInstalled(deviceSerial, harnessPackage, timeout);

        boolean testInstalled = isPackageInstalled(deviceSerial, testPackage, timeout);

        if (harnessInstalled && testInstalled) {

            log.info("ATF harness and instrumentation APK are already installed");

            return;
        }

        /*
         * Main/debug APK is missing.
         */
        if (!harnessInstalled) {

            installApk(deviceSerial, properties.getHarnessApkPath(), "ATF harness APK", timeout);
        }

        /*
         * androidTest APK is missing.
         */
        if (!testInstalled) {

            installApk(deviceSerial, properties.getHarnessTestApkPath(), "ATF instrumentation APK", timeout);
        }

        /*
         * Verify both after installation.
         */
        if (!isPackageInstalled(deviceSerial, harnessPackage, timeout)) {

            throw new IllegalStateException("ATF harness APK installation failed. " + "Package not found: " + harnessPackage);
        }

        if (!isPackageInstalled(deviceSerial, testPackage, timeout)) {

            throw new IllegalStateException("ATF instrumentation APK installation failed. " + "Package not found: " + testPackage);
        }

        log.info("ATF harness and instrumentation APKs installed successfully");
    }

    private boolean isPackageInstalled(String deviceSerial, String packageName, int timeout) {

        if (packageName == null || packageName.isBlank()) {
            throw new IllegalStateException("Package name is not configured");
        }

        log.info("Checking installed package: {}", packageName);

        AdbCommandExecutor.Result result = adb.run(deviceSerial, List.of("shell", "pm", "path", packageName), timeout);

        boolean installed = result.isSuccess() && result.output() != null && result.output().contains("package:");

        if (installed) {
            log.info("Package is installed: {}", packageName);
        } else {
            log.info("Package is NOT installed: {}", packageName);
        }

        return installed;
    }

    private void installApk(String deviceSerial, String apkPath, String description, int timeout) {

        if (apkPath == null || apkPath.isBlank()) {
            throw new IllegalStateException(description + " path is not configured");
        }

        Path apk = Paths.get(apkPath).toAbsolutePath().normalize();

        if (!Files.exists(apk)) {
            throw new IllegalStateException(description + " does not exist: " + apk);
        }

        log.info("Installing {}: {}", description, apk);

        AdbCommandExecutor.Result result = adb.run(deviceSerial, List.of("install", "-r", apk.toString()), timeout);

        if (!result.isSuccess() || !result.output().contains("Success")) {

            throw new IllegalStateException("Failed to install " + description + " on " + deviceSerial + "\nAPK: " + apk + "\nADB output:\n" + result.output());
        }

        log.info("{} installed successfully", description);
    }

    /**
     * Installs the ATF harness APK on the device.
     */
    private void installHarness(String deviceSerial, int timeout) {
        String apkPath = properties.getHarnessApkPath();
        if (apkPath == null || apkPath.isBlank()) {
            throw new IllegalStateException("ATF harness APK path is not configured. " + "Configure atf.harness-apk-path.");
        }
        Path apk = Paths.get(apkPath).toAbsolutePath().normalize();
        if (!Files.exists(apk)) {
            throw new IllegalStateException("ATF harness APK does not exist: " + apk);
        }
        log.info("ATF harness is not installed. Installing APK: {}", apk);
        AdbCommandExecutor.Result installResult = adb.run(deviceSerial, List.of("install", "-r", apk.toString()), timeout);
        if (!installResult.isSuccess() || !installResult.output().contains("Success")) {
            throw new IllegalStateException("Failed to install ATF harness APK on " + deviceSerial + ". APK=" + apk + "\nADB output:\n" + installResult.output());
        }
        log.info("ATF harness APK installed successfully: {}", apk); /* * Verify installation after adb install. */
        if (!isHarnessInstalled(deviceSerial, timeout)) {
            throw new IllegalStateException("ATF harness APK installation reported success, " + "but package " + properties.getHarnessTestPackage() + " could not be found on device " + deviceSerial);
        }
    }
}
