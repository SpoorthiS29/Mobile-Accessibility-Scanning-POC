package com.fireflink.a11y.atf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.UUID;

/**
 * adb entry point. Guarded by {@code android.permission.DUMP} in the manifest,
 * so only the shell (adb / Appium {@code mobile: shell}) and the system can
 * send it. {@code am broadcast} waits for the result, so the reply is printed
 * as {@code Broadcast completed: result=<code>, data="<data>"}.
 *
 * <pre>
 * am broadcast -f 0x20 -n com.fireflink.a11y.atf/.ScanCommandReceiver \
 *     -a com.fireflink.a11y.atf.action.PING
 *   → result=1 data="READY" | result=1 data="BUSY" | result=2 data="NOT_CONNECTED"
 *
 * am broadcast -f 0x20 -n com.fireflink.a11y.atf/.ScanCommandReceiver \
 *     -a com.fireflink.a11y.atf.action.SCAN --es scanId &lt;id&gt; --ei scrollCount 3
 *   → result=1 data="STARTED:&lt;id&gt;" | result=3 data="BUSY" | result=2 data="NOT_CONNECTED"
 * </pre>
 */
public class ScanCommandReceiver extends BroadcastReceiver {

    static final String ACTION_PING = "com.fireflink.a11y.atf.action.PING";
    static final String ACTION_SCAN = "com.fireflink.a11y.atf.action.SCAN";

    static final String EXTRA_SCAN_ID = "scanId";
    static final String EXTRA_SCROLL_COUNT = "scrollCount";

    static final int RESULT_OK = 1;
    static final int RESULT_NOT_CONNECTED = 2;
    static final int RESULT_BUSY = 3;
    static final int RESULT_UNKNOWN_ACTION = 4;

    private static final String TAG = "ScanCommandReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        AtfAccessibilityService service = AtfAccessibilityService.get();
        if (service == null) {
            Log.w(TAG, action + ": accessibility service is not connected");
            reply(RESULT_NOT_CONNECTED, "NOT_CONNECTED");
            return;
        }
        if (ACTION_PING.equals(action)) {
            reply(RESULT_OK, service.isScanning() ? "BUSY" : "READY");
            return;
        }
        if (ACTION_SCAN.equals(action)) {
            String scanId = intent.getStringExtra(EXTRA_SCAN_ID);
            if (scanId == null || scanId.trim().isEmpty()) {
                scanId = UUID.randomUUID().toString();
            }
            int scrollCount = scrollCount(intent);
            AtfAccessibilityService.StartResult result = service.startScan(scanId, scrollCount);
            if (result == AtfAccessibilityService.StartResult.STARTED) {
                reply(RESULT_OK, "STARTED:" + scanId);
            } else {
                reply(RESULT_BUSY, "BUSY");
            }
            return;
        }
        reply(RESULT_UNKNOWN_ACTION, "UNKNOWN_ACTION");
    }

    /** Accepts {@code --ei scrollCount 3} or {@code --es scrollCount 3}. */
    private static int scrollCount(Intent intent) {
        Object raw = intent.getExtras() != null ? intent.getExtras().get(EXTRA_SCROLL_COUNT) : null;
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        if (raw instanceof String) {
            try {
                return Math.max(0, Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void reply(int code, String data) {
        if (isOrderedBroadcast()) {
            setResult(code, data, null);
        }
        Log.i(TAG, "reply result=" + code + " data=" + data);
    }
}
