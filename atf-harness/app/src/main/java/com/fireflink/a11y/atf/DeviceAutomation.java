package com.fireflink.a11y.atf;

import android.graphics.Bitmap;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * The slice of device access {@link AtfScanner} needs. It used to come from
 * {@code Instrumentation#getUiAutomation()}; it is now backed by
 * {@link AtfAccessibilityService}, so the scan never takes the device's single
 * UiAutomation slot away from Appium's UiAutomator2 server.
 */
interface DeviceAutomation {

    /** Root of the active window, or {@code null} while the screen is changing. */
    AccessibilityNodeInfo getRootInActiveWindow();

    /** On-screen windows (requires {@code flagRetrieveInteractiveWindows}). */
    List<AccessibilityWindowInfo> getWindows();

    /**
     * Full-display screenshot as a software ARGB_8888 bitmap, or {@code null}
     * when the platform cannot capture (below Android 11) or capture failed.
     * Callers already treat a {@code null} screenshot as "skip contrast/crops".
     */
    Bitmap takeScreenshot();

    /** Straight-line swipe; blocks until the gesture completes or is cancelled. */
    void swipe(int x1, int y1, int x2, int y2, long durationMs) throws InterruptedException;

    /** Whether the harness may write rotation settings (WRITE_SETTINGS app-op). */
    boolean canChangeRotation();

    /** Snapshot of the user's auto-rotate / locked-rotation settings. */
    RotationState saveRotationState();

    /** Locks the display to a {@link android.view.Surface} rotation constant. */
    void freezeRotation(int surfaceRotation);

    /** Puts back what {@link #saveRotationState()} captured. */
    void restoreRotationState(RotationState state);

    /** Values of {@code Settings.System.ACCELEROMETER_ROTATION} / {@code USER_ROTATION}. */
    final class RotationState {
        final int accelerometerRotation;
        final int userRotation;

        RotationState(int accelerometerRotation, int userRotation) {
            this.accelerometerRotation = accelerometerRotation;
            this.userRotation = userRotation;
        }
    }
}
