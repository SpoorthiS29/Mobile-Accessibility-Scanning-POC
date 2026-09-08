package com.poc.a11y.atf;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around shelling out to {@code adb}. Kept isolated so
 * {@link AtfHarnessRunner} doesn't deal with ProcessBuilder plumbing.
 */
@Component
public class AdbCommandExecutor {

    private final AtfProperties properties;

    public AdbCommandExecutor(AtfProperties properties) {
        this.properties = properties;
    }

    public record Result(int exitCode, String output) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code adb [-s deviceSerial] <args...>} and waits (up to
     * {@code timeoutSeconds}) for it to finish, capturing combined
     * stdout+stderr.
     */
    public Result run(String deviceSerial, List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(properties.getAdbPath());
        if (deviceSerial != null && !deviceSerial.isBlank()) {
            command.add("-s");
            command.add(deviceSerial);
        }
        command.addAll(args);

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (InputStream is = process.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "adb command timed out after " + timeoutSeconds + "s: " + String.join(" ", command));
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run adb command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adb command interrupted: " + String.join(" ", command), e);
        }
    }
}
