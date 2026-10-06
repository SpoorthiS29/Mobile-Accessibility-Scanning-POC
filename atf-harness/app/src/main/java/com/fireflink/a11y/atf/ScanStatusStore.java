package com.fireflink.a11y.atf;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Writes {@code atf-status.json} next to {@code atf-result.json} in
 * {@code getExternalFilesDir(null)}. The Spring Boot side polls this file
 * (via {@code mobile: shell cat}) to learn when a scan it triggered has
 * finished, then pulls the result JSON.
 *
 * <pre>{"scanId":"…","state":"RUNNING|DONE|FAILED","resultFile":"…","error":"…","updatedAt":123}</pre>
 *
 * Written to a temp file and renamed so a poll never reads a half-written file.
 */
final class ScanStatusStore {

    static final String STATUS_FILE_NAME = "atf-status.json";

    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_DONE = "DONE";
    static final String STATE_FAILED = "FAILED";

    private static final String TAG = "ScanStatusStore";

    private ScanStatusStore() {
    }

    static File filesDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            throw new IllegalStateException("getExternalFilesDir(null) returned null; cannot write ATF output");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    static void write(Context context, String scanId, String state, String resultFile, String error) {
        try {
            JSONObject json = new JSONObject();
            json.put("scanId", scanId);
            json.put("state", state);
            if (resultFile != null) {
                json.put("resultFile", resultFile);
            }
            if (error != null) {
                json.put("error", error);
            }
            json.put("updatedAt", System.currentTimeMillis());

            File dir = filesDir(context);
            File tmp = new File(dir, STATUS_FILE_NAME + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                writer.write(json.toString());
            }
            File target = new File(dir, STATUS_FILE_NAME);
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("Could not rename " + tmp + " to " + target);
            }
        } catch (JSONException | java.io.IOException | RuntimeException e) {
            Log.e(TAG, "Could not write scan status " + state + " for " + scanId, e);
        }
    }

    /** Removes the previous scan's result so the server can never read a stale one. */
    static void deleteResult(Context context) {
        File result = new File(filesDir(context), AtfScanner.RESULT_FILE_NAME);
        if (result.exists() && !result.delete()) {
            Log.w(TAG, "Could not delete old " + result.getAbsolutePath());
        }
    }
}
