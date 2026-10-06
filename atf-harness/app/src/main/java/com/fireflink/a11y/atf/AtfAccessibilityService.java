package com.fireflink.a11y.atf;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-APK ATF harness. Enabled once over adb
 * ({@code settings put secure enabled_accessibility_services …}); after that
 * every scan is triggered with a broadcast to {@link ScanCommandReceiver} and
 * runs here on a background thread.
 *
 * <p>Unlike the old {@code am instrument} harness this never connects a
 * UiAutomation, so Appium's UiAutomator2 server is not killed and does not
 * need restarting. The Appium session must be created with
 * {@code appium:disableSuppressAccessibilityService=true}; otherwise
 * UiAutomator2 suppresses (unbinds) every accessibility service, this one
 * included, for as long as its session is alive.
 */
public class AtfAccessibilityService extends AccessibilityService implements DeviceAutomation {

    private static final String TAG = "AtfA11yService";

    private static final long GESTURE_EXTRA_WAIT_MS = 3_000L;
    private static final long SCREENSHOT_TIMEOUT_MS = 5_000L;
    private static final int SCREENSHOT_ATTEMPTS = 4;
    /** Platform rate limit between accessibility screenshots is ~333 ms. */
    private static final long SCREENSHOT_RETRY_DELAY_MS = 400L;

    private static volatile AtfAccessibilityService instance;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService scanExecutor;

    enum StartResult { STARTED, BUSY }

    /** The connected service, or {@code null} when it is disabled or suppressed. */
    static AtfAccessibilityService get() {
        return instance;
    }

    boolean isScanning() {
        return scanning.get();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        scanExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "atf-scan");
            thread.setDaemon(true);
            return thread;
        });
        instance = this;
        Log.i(TAG, "ATF accessibility service connected (SDK " + Build.VERSION.SDK_INT + ")");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are only subscribed so the framework keeps this service's
        // node cache fresh; the scan reads windows on demand.
    }

    @Override
    public void onInterrupt() {
        // No feedback to interrupt.
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    private void disconnect() {
        if (instance == this) {
            instance = null;
        }
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
        Log.i(TAG, "ATF accessibility service disconnected");
    }

    /**
     * Starts a scan of whatever is in the foreground. Returns immediately; the
     * outcome is reported through {@link ScanStatusStore}.
     */
    StartResult startScan(String scanId, int scrollCount) {
        if (!scanning.compareAndSet(false, true)) {
            return StartResult.BUSY;
        }
        try {
            ScanStatusStore.deleteResult(this);
            ScanStatusStore.write(this, scanId, ScanStatusStore.STATE_RUNNING, null, null);
            scanExecutor.execute(() -> runScan(scanId, scrollCount));
            return StartResult.STARTED;
        } catch (RuntimeException e) {
            scanning.set(false);
            ScanStatusStore.write(this, scanId, ScanStatusStore.STATE_FAILED, null, stackTrace(e));
            throw e;
        }
    }

    private void runScan(String scanId, int scrollCount) {
        long started = SystemClock.uptimeMillis();
        try {
            Log.i(TAG, "Scan " + scanId + " started, scrollCount=" + scrollCount);
            File out = new AtfScanner(this, this).run(scrollCount);
            ScanStatusStore.write(this, scanId, ScanStatusStore.STATE_DONE, out.getAbsolutePath(), null);
            Log.i(TAG, "Scan " + scanId + " finished in " + (SystemClock.uptimeMillis() - started) + " ms");
        } catch (Throwable t) {
            Log.e(TAG, "Scan " + scanId + " failed", t);
            ScanStatusStore.write(this, scanId, ScanStatusStore.STATE_FAILED, null, stackTrace(t));
        } finally {
            scanning.set(false);
        }
    }

    // ------------------------------------------------------------------
    // DeviceAutomation
    // ------------------------------------------------------------------

    @Override
    public List<AccessibilityWindowInfo> getWindows() {
        List<AccessibilityWindowInfo> windows = super.getWindows();
        return windows != null ? windows : Collections.emptyList();
    }

    @Override
    public Bitmap takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshots need Android 11+; contrast checks and crops are skipped");
            return null;
        }
        for (int attempt = 1; attempt <= SCREENSHOT_ATTEMPTS; attempt++) {
            AtomicReference<Bitmap> bitmap = new AtomicReference<>();
            AtomicInteger errorCode = new AtomicInteger(Integer.MIN_VALUE);
            CountDownLatch done = new CountDownLatch(1);
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    try {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            // ATF contrast + crops call getPixel(s); HARDWARE bitmaps do not allow that.
                            bitmap.set(hardware.copy(Bitmap.Config.ARGB_8888, false));
                            hardware.recycle();
                        }
                    } finally {
                        buffer.close();
                        done.countDown();
                    }
                }

                @Override
                public void onFailure(int code) {
                    errorCode.set(code);
                    done.countDown();
                }
            });
            try {
                if (!done.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Screenshot timed out (attempt " + attempt + ")");
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (bitmap.get() != null) {
                return bitmap.get();
            }
            int code = errorCode.get();
            Log.w(TAG, "Screenshot failed with code " + code + " (attempt " + attempt + ")");
            if (code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                    && code != ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) {
                return null;
            }
            SystemClock.sleep(SCREENSHOT_RETRY_DELAY_MS);
        }
        return null;
    }

    @Override
    public void swipe(int x1, int y1, int x2, int y2, long durationMs) throws InterruptedException {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1L, durationMs)))
                .build();
        CountDownLatch done = new CountDownLatch(1);
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription description) {
                Log.w(TAG, "Swipe gesture was cancelled");
                done.countDown();
            }
        }, mainHandler);
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture refused the swipe (canPerformGestures missing?)");
            return;
        }
        if (!done.await(durationMs + GESTURE_EXTRA_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Swipe gesture did not report completion in time");
        }
    }

    @Override
    public boolean canChangeRotation() {
        return Settings.System.canWrite(this);
    }

    @Override
    public RotationState saveRotationState() {
        return new RotationState(
                Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1),
                Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION, Surface.ROTATION_0));
    }

    @Override
    public void freezeRotation(int surfaceRotation) {
        // Same settings WindowManager#freezeRotation (and UiAutomation#setRotation) writes.
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
        Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, surfaceRotation);
    }

    @Override
    public void restoreRotationState(RotationState state) {
        if (state == null) {
            return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, state.userRotation);
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION,
                state.accelerometerRotation);
    }

    private static String stackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        return text.length() > 8_000 ? text.substring(0, 8_000) : text;
    }
}
