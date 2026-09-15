package com.poc.a11y.atf;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package version as read from a local APK or from {@code dumpsys package}.
 */
record PackageIdentity(String packageName, String versionCode, String versionName, Long lastUpdateTimeMs) {

    private static final Pattern VERSION_CODE = Pattern.compile("versionCode=(\\d+)");
    private static final Pattern VERSION_NAME = Pattern.compile("versionName=(\\S+)");
    private static final Pattern LAST_UPDATE_TEXT =
            Pattern.compile("lastUpdateTime=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern LAST_UPDATE_MILLIS = Pattern.compile("lastUpdateTime=(\\d{10,})");
    private static final DateTimeFormatter LAST_UPDATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CLOCK_SKEW_MS = 2_000L;

    static PackageIdentity fromDumpsys(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher code = VERSION_CODE.matcher(output);
        String versionCode = code.find() ? code.group(1) : null;
        Matcher name = VERSION_NAME.matcher(output);
        String versionName = name.find() ? name.group(1) : null;
        Long lastUpdate = parseLastUpdateTime(output);
        if (versionCode == null && lastUpdate == null && !output.contains("Package [")) {
            return null;
        }
        return new PackageIdentity(null, versionCode, versionName, lastUpdate);
    }

    /**
     * Reinstall when the package is missing, the APK version differs, or — when
     * version cannot be read (typical of androidTest APKs) / the APK file is
     * newer than the installed package — so a rebuilt APK is not skipped.
     */
    static boolean needsInstall(PackageIdentity installed, PackageIdentity apk, long apkLastModifiedMs) {
        if (installed == null) {
            return true;
        }
        if (apk != null && apk.versionCode() != null && installed.versionCode() != null) {
            boolean sameVersion = installed.versionCode().equals(apk.versionCode())
                    && (apk.versionName() == null
                    || installed.versionName() == null
                    || apk.versionName().equals(installed.versionName()));
            if (!sameVersion) {
                return true;
            }
            return isApkNewerThanInstalled(apkLastModifiedMs, installed.lastUpdateTimeMs());
        }
        return installed.lastUpdateTimeMs() == null
                || isApkNewerThanInstalled(apkLastModifiedMs, installed.lastUpdateTimeMs());
    }

    private static boolean isApkNewerThanInstalled(long apkLastModifiedMs, Long lastUpdateTimeMs) {
        if (lastUpdateTimeMs == null) {
            return false;
        }
        return apkLastModifiedMs > lastUpdateTimeMs + CLOCK_SKEW_MS;
    }

    private static Long parseLastUpdateTime(String output) {
        Matcher text = LAST_UPDATE_TEXT.matcher(output);
        if (text.find()) {
            try {
                return LocalDateTime.parse(text.group(1), LAST_UPDATE_FORMAT)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // fall through to millis form
            }
        }
        Matcher millis = LAST_UPDATE_MILLIS.matcher(output);
        if (millis.find()) {
            return Long.parseLong(millis.group(1));
        }
        return null;
    }

    String displayVersion() {
        if (versionName != null && versionCode != null) {
            return versionName + " (versionCode=" + versionCode + ")";
        }
        if (versionCode != null) {
            return "versionCode=" + versionCode;
        }
        return "unknown";
    }
}
