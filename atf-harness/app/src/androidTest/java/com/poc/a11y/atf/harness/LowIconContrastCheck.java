package com.poc.a11y.atf.harness;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
import com.google.android.apps.common.testing.accessibility.framework.uielement.WindowHierarchyElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WCAG 1.4.11 Non-text Contrast (Level AA).
 *
 * <p>Checks interactive ImageView/ImageButton controls by estimating
 * the contrast between the icon and its surrounding background using
 * pixels from the current viewport screenshot.
 *
 * <p>This is a heuristic pixel-sampling check. Gradient backgrounds,
 * photographs, transparency, shadows, and complex icons may require
 * manual verification.
 */
final class LowIconContrastCheck implements CustomHierarchyCheck {

    static final String CHECK_NAME = "LowIconContrastCheck";
    static final int RESULT_ID_LOW_CONTRAST = 1;

    /**
     * WCAG 1.4.11 requires at least 3:1 contrast for graphical
     * user interface components.
     */
    private static final double MIN_ICON_CONTRAST = 3.0;

    /**
     * Minimum icon area to make pixel sampling meaningful.
     */
    private static final int MIN_SAMPLE_SIZE_PX = 20;

    /**
     * Ignore very large image regions because they are more likely
     * to represent photos/banners than glyph-style icons.
     */
    private static final double MAX_SCREEN_FRACTION = 0.50;

    /**
     * Number of pixels around the center/border used for sampling.
     */
    private static final int SAMPLE_GRID_SIZE = 5;

    /**
     * Width of the border band used for background sampling.
     */
    private static final int BORDER_BAND_PX = 3;

    private static final String TAG = "LowIconContrastCheck";

    @Override
    public String getCheckName() {
        return CHECK_NAME;
    }

    /**
     * Hierarchy-only entry point.
     *
     * <p>The icon contrast check needs the screenshot, so without it
     * no finding can be produced.
     */
    @Override
    public List<AtfIssueRecord> evaluate(
            AccessibilityHierarchy hierarchy,
            ViewportCropper cropper) {

        return new ArrayList<>();
    }

    /**
     * Evaluate the current viewport using its screenshot.
     */
    @Override
    public List<AtfIssueRecord> evaluate(AccessibilityHierarchy hierarchy, ViewportCropper cropper, Bitmap screenshot) {

        List<AtfIssueRecord> findings = new ArrayList<>();

        if (hierarchy == null || screenshot == null || screenshot.isRecycled()) {
            return findings;
        }

        int sw = screenshot.getWidth();
        int sh = screenshot.getHeight();

        if (sw <= 0 || sh <= 0) {
            return findings;
        }

        /*
         * ------------------------------------------------------------
         * Coordinate-space sanity check.
         *
         * AccessibilityHierarchy bounds are in screen coordinates.
         * The screenshot should use approximately the same coordinate
         * space. If not, pixel sampling would be meaningless.
         * ------------------------------------------------------------
         */
        int maxNodeRight = 0;
        int maxNodeBottom = 0;

        for (WindowHierarchyElement window : hierarchy.getAllWindows()) {

            if (window == null) {
                continue;
            }

            for (ViewHierarchyElement node : window.getAllViews()) {

                if (node == null) {
                    continue;
                }

                Rect bounds = boundsOrNull(node);

                if (bounds == null) {
                    continue;
                }

                maxNodeRight = Math.max(maxNodeRight, bounds.right);
                maxNodeBottom = Math.max(maxNodeBottom, bounds.bottom);
            }
        }

        if (maxNodeRight > 0 && maxNodeBottom > 0) {

            double xRatio = (double) maxNodeRight / sw;
            double yRatio = (double) maxNodeBottom / sh;

            if (xRatio < 0.5
                    || xRatio > 1.5
                    || yRatio < 0.5
                    || yRatio > 1.5) {

                Log.w(
                        TAG,
                        "Icon contrast check skipped: UI hierarchy bounds and "
                                + "screenshot dimensions do not appear to use "
                                + "the same coordinate space. "
                                + "bounds=" + maxNodeRight + "x" + maxNodeBottom
                                + ", screenshot=" + sw + "x" + sh
                );

                return findings;
            }
        }

        /*
         * ------------------------------------------------------------
         * Evaluate every node in every accessibility window.
         * ------------------------------------------------------------
         */
        for (WindowHierarchyElement window : hierarchy.getAllWindows()) {

            if (window == null) {
                continue;
            }

            for (ViewHierarchyElement node : window.getAllViews()) {

                checkNode(
                        node,
                        screenshot,
                        sw,
                        sh,
                        findings,
                        cropper
                );
            }
        }

        return findings;
    }

    private static void checkNode(
            ViewHierarchyElement node,
            Bitmap screenshot,
            int screenWidth,
            int screenHeight,
            List<AtfIssueRecord> findings,
            ViewportCropper cropper) {

        if (node == null) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 1. Identify image/icon controls.
         * ------------------------------------------------------------
         */
        String className = charSeq(node.getClassName());

        boolean isImage =
                className.contains("ImageView")
                        || className.contains("ImageButton");

        if (!isImage) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 2. Only interactive icons.
         *
         * Decorative images are not evaluated.
         * ------------------------------------------------------------
         */
        if (!node.isClickable() && !node.isFocusable()) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 3. Disabled controls are exempt.
         * ------------------------------------------------------------
         */
        if (!node.isEnabled()) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 4. Obtain node bounds.
         * ------------------------------------------------------------
         */
        Rect bounds = boundsOrNull(node);

        if (bounds == null) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 5. Clamp bounds to screenshot.
         * ------------------------------------------------------------
         */
        int left = Math.max(0, bounds.left);
        int top = Math.max(0, bounds.top);
        int right = Math.min(screenWidth, bounds.right);
        int bottom = Math.min(screenHeight, bounds.bottom);

        int boxWidth = right - left;
        int boxHeight = bottom - top;

        /*
         * ------------------------------------------------------------
         * 6. Ignore very small regions.
         * ------------------------------------------------------------
         */
        if (boxWidth < MIN_SAMPLE_SIZE_PX
                || boxHeight < MIN_SAMPLE_SIZE_PX) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 7. Ignore very large regions.
         *
         * These are more likely photos/banners than icon-style
         * graphical controls.
         * ------------------------------------------------------------
         */
        if (boxWidth > screenWidth * MAX_SCREEN_FRACTION
                || boxHeight > screenHeight * MAX_SCREEN_FRACTION) {
            return;
        }

        /*
         * ------------------------------------------------------------
         * 8. Sample icon and background colors.
         * ------------------------------------------------------------
         */
        int iconColor = sampleCenterMedian(
                screenshot,
                left,
                top,
                right,
                bottom
        );

        int backgroundColor = sampleBorderMedian(
                screenshot,
                left,
                top,
                right,
                bottom
        );

        /*
         * ------------------------------------------------------------
         * 9. Calculate WCAG contrast ratio.
         * ------------------------------------------------------------
         */
        double ratio = contrastRatio(
                iconColor,
                backgroundColor
        );

        /*
         * ------------------------------------------------------------
         * 10. Report only when below 3:1.
         * ------------------------------------------------------------
         */
        if (ratio >= MIN_ICON_CONTRAST) {
            return;
        }

        String message = String.format(
                java.util.Locale.US,
                "Interactive icon has an estimated contrast ratio of %.2f:1 "
                        + "against its background (pixel-sampled from the "
                        + "screenshot). WCAG 1.4.11 requires at least 3:1 "
                        + "for graphical UI components. This is an "
                        + "approximation — verify manually, especially on "
                        + "gradient or photo backgrounds.",
                ratio
        );

        AtfIssueRecord record = new AtfIssueRecord(
                CHECK_NAME,
                LowIconContrastCheck.class.getName(),
                AccessibilityCheckResultType.WARNING,
                RESULT_ID_LOW_CONTRAST,
                message,
                node
        );

        /*
         * Crop immediately while this viewport screenshot and hierarchy
         * are still available.
         */
        if (cropper != null) {
            cropper.crop(record);
        }

        findings.add(record);
    }

    /**
     * Returns the median RGB color from a small grid around the
     * center of the icon bounds.
     *
     * <p>The median is used instead of one pixel so a single unusual
     * pixel does not dominate the estimate.
     */
    private static int sampleCenterMedian(
            Bitmap bitmap,
            int left,
            int top,
            int right,
            int bottom) {

        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2;

        int half = SAMPLE_GRID_SIZE / 2;

        List<Integer> red = new ArrayList<>();
        List<Integer> green = new ArrayList<>();
        List<Integer> blue = new ArrayList<>();

        for (int dy = -half; dy <= half; dy++) {

            for (int dx = -half; dx <= half; dx++) {

                int x = clamp(
                        centerX + dx,
                        left,
                        right - 1
                );

                int y = clamp(
                        centerY + dy,
                        top,
                        bottom - 1
                );

                int color = bitmap.getPixel(x, y);

                red.add(Color.red(color));
                green.add(Color.green(color));
                blue.add(Color.blue(color));
            }
        }

        return Color.rgb(
                median(red),
                median(green),
                median(blue)
        );
    }

    /**
     * Samples the border of the icon bounds and returns the median
     * RGB color.
     *
     * <p>The border is treated as the estimated background surrounding
     * the icon.
     */
    private static int sampleBorderMedian(
            Bitmap bitmap,
            int left,
            int top,
            int right,
            int bottom) {

        List<Integer> red = new ArrayList<>();
        List<Integer> green = new ArrayList<>();
        List<Integer> blue = new ArrayList<>();

        int band = Math.min(
                BORDER_BAND_PX,
                Math.min(right - left, bottom - top) / 4
        );

        if (band <= 0) {
            return bitmap.getPixel(
                    clamp((left + right) / 2, left, right - 1),
                    clamp((top + bottom) / 2, top, bottom - 1)
            );
        }

        /*
         * Top and bottom border bands.
         */
        for (int y = top; y < Math.min(top + band, bottom); y++) {

            for (int x = left; x < right; x++) {

                addPixel(
                        bitmap.getPixel(x, y),
                        red,
                        green,
                        blue
                );
            }
        }

        for (int y = Math.max(top, bottom - band); y < bottom; y++) {

            for (int x = left; x < right; x++) {

                addPixel(
                        bitmap.getPixel(x, y),
                        red,
                        green,
                        blue
                );
            }
        }

        /*
         * Left and right border bands.
         */
        for (int y = top + band; y < bottom - band; y++) {

            for (int x = left; x < Math.min(left + band, right); x++) {

                addPixel(
                        bitmap.getPixel(x, y),
                        red,
                        green,
                        blue
                );
            }

            for (int x = Math.max(left, right - band); x < right; x++) {

                addPixel(
                        bitmap.getPixel(x, y),
                        red,
                        green,
                        blue
                );
            }
        }

        if (red.isEmpty()) {
            return bitmap.getPixel(
                    clamp((left + right) / 2, left, right - 1),
                    clamp((top + bottom) / 2, top, bottom - 1)
            );
        }

        return Color.rgb(
                median(red),
                median(green),
                median(blue)
        );
    }

    private static void addPixel(
            int color,
            List<Integer> red,
            List<Integer> green,
            List<Integer> blue) {

        red.add(Color.red(color));
        green.add(Color.green(color));
        blue.add(Color.blue(color));
    }

    /**
     * Calculates the WCAG relative-luminance contrast ratio.
     */
    private static double contrastRatio(
            int color1,
            int color2) {

        double luminance1 = relativeLuminance(color1);
        double luminance2 = relativeLuminance(color2);

        double lighter = Math.max(luminance1, luminance2);
        double darker = Math.min(luminance1, luminance2);

        return (lighter + 0.05) / (darker + 0.05);
    }

    /**
     * Converts an sRGB color to WCAG relative luminance.
     */
    private static double relativeLuminance(int color) {

        double red = linearize(Color.red(color) / 255.0);
        double green = linearize(Color.green(color) / 255.0);
        double blue = linearize(Color.blue(color) / 255.0);

        return 0.2126 * red
                + 0.7152 * green
                + 0.0722 * blue;
    }

    private static double linearize(double channel) {

        if (channel <= 0.03928) {
            return channel / 12.92;
        }

        return Math.pow(
                (channel + 0.055) / 1.055,
                2.4
        );
    }

    private static int median(List<Integer> values) {

        if (values == null || values.isEmpty()) {
            return 0;
        }

        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int middle = sorted.size() / 2;

        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }

        return (
                sorted.get(middle - 1)
                        + sorted.get(middle)
        ) / 2;
    }

    private static Rect boundsOrNull(ViewHierarchyElement node) {

        if (node == null) {
            return null;
        }

        var bounds = node.getBoundsInScreen();

        if (bounds == null) {
            return null;
        }

        int left = bounds.getLeft();
        int top = bounds.getTop();
        int right = bounds.getRight();
        int bottom = bounds.getBottom();

        if (right <= left || bottom <= top) {
            return null;
        }

        return new Rect(
                left,
                top,
                right,
                bottom
        );
    }

    private static int clamp(
            int value,
            int min,
            int max) {

        return Math.max(
                min,
                Math.min(max, value)
        );
    }

    private static String charSeq(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}