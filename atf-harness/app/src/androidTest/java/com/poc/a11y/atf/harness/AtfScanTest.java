    package com.poc.a11y.atf.harness;

    import android.app.Instrumentation;
    import android.app.UiAutomation;
    import android.content.Context;
    import android.graphics.Bitmap;
    import android.graphics.Canvas;
    import android.graphics.Color;
    import android.graphics.Paint;
    import android.graphics.Rect;
    import android.os.Bundle;
    import android.os.ParcelFileDescriptor;
    import android.os.SystemClock;
    import android.util.DisplayMetrics;
    import android.util.Log;
    import android.view.Display;
    import android.view.Surface;
    import android.view.WindowManager;
    import android.view.accessibility.AccessibilityNodeInfo;
    import android.view.accessibility.AccessibilityWindowInfo;

    import androidx.test.ext.junit.runners.AndroidJUnit4;
    import androidx.test.platform.app.InstrumentationRegistry;

    import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult;
    import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
    import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset;
    import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
    import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
    import com.google.android.apps.common.testing.accessibility.framework.Parameters;
    import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid;
    import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement;
    import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage;
    import com.google.common.collect.ImmutableSet;

    import org.junit.Test;
    import org.junit.runner.RunWith;

    import java.io.File;
    import java.io.FileOutputStream;
    import java.io.OutputStreamWriter;
    import java.io.Writer;
    import java.nio.charset.StandardCharsets;
    import java.util.ArrayList;
    import java.util.Collections;
    import java.util.HashMap;
    import java.util.HashSet;
    import java.util.LinkedHashSet;
    import java.util.List;
    import java.util.Locale;
    import java.util.Map;
    import java.util.Set;

    /**
     * Scans the already-visible app with real ATF. Scrolls nearly a full page with
     * a small overlap so rows are not skipped, and dedupes by check + element
     * identity (resource / label when present; quantized position for unlabeled
     * siblings) so the same widget is not reported twice after a swipe. Sticky
     * chrome that repaints with a slightly different label is also collapsed by
     * bounds overlap. Clipped / zero-area nodes and edge slivers are dropped.
     * A parent card and the distinct widgets inside it (image, title, price)
     * stay as separate issues; only near-identical bounds of the same check
     * are treated as the same widget.
     * Crops include surrounding context plus a red highlight on the failing widget,
     * positioned from bounds sampled next to the screenshot rather than from the
     * ATF snapshot, because Amazon's web feed repaints while the checks run.
     * Per-viewport custom checks (see {@link CustomHierarchyChecks}) run
     * after ATF on the same hierarchy, still inside the scroll loop. ATF
     * findings are cropped after the preset batch. Custom findings are
     * cropped in the same node walk that flags them; after custom dedup,
     * crops that belong only to dropped duplicates are deleted. Both
     * lists are then appended to the merged result.
     *
     * <p>This class does not launch the app under test. The Spring Boot / Appium
     * side must already have the target UI in the foreground (physical USB device
     * or Sauce Labs virtual device) before {@code am instrument} starts.
     */
    @RunWith(AndroidJUnit4.class)
    public class AtfScanTest {

        private static final String TAG = "AtfScanTest";
        static final String RESULT_FILE_NAME = "atf-result.json";
        static final String SHOTS_DIR_NAME = "shots";

        /**
         * After the first viewport, only keep findings whose center is below this
         * fraction of the screen. High enough that a near-full-page swipe does not
         * re-accept content already scanned in the overlap zone.
         */
        private static final float NEW_BAND_TOP_FRACTION = 0.20f;

        /** Quantize unlabeled-widget centers so siblings stay distinct without using raw Y. */
        private static final int POSITION_BUCKET_PX = 32;

        /** Same check + class with this IoU against an already-kept finding is a sticky duplicate. */
        private static final float SPATIAL_DEDUPE_IOU = 0.65f;

        /** Context around the failing widget so the crop is recognizable. */
        private static final int CROP_PADDING_PX = 48;

        /** Small targets are expanded to at least this crop so the shot is not a sliver. */
        private static final int MIN_CROP_WIDTH_PX = 240;
        private static final int MIN_CROP_HEIGHT_PX = 160;

        /** Nodes thinner than this (px) are clipped at the fold and produce sliver crops. */
        private static final int MIN_VISIBLE_EDGE_PX = 8;

        /** Edge-hugging strips narrower than this are carousel leftovers, not real widgets. */
        private static final int EDGE_SLIVER_PX = 80;

        /**
         * Shared speakable prefix used only when two findings already have
         * nearly the same bounds (same widget, not a parent card vs a child).
         */
        private static final int NESTED_LABEL_PREFIX_MIN = 24;

        /** Matched pairs below this delta are treated as sticky chrome, not scroll. */
        private static final long MIN_MEANINGFUL_SHIFT_PX = 20;
        /** Need at least this many unambiguous matches to trust the measured median. */
        private static final int MIN_SHIFT_SAMPLES = 3;
        /** Fallback estimate (fraction of screen height) when too few elements survive the scroll unchanged. */
        private static final float DEFAULT_SHIFT_FRACTION = 0.55f;

        //Orientation
        /** WCAG 1.3.4 Orientation custom check. */
        private static final String ORIENTATION_CHECK_NAME = "Orientation";

        /** Landscape rotation used to test whether the foreground app adapts. */
        private static final int ORIENTATION_TEST_ROTATION = UiAutomation.ROTATION_FREEZE_90;

        /** Time allowed for the foreground application to settle after rotation. */
        private static final long ORIENTATION_SETTLE_MS = 1200L;

        @Test
        public void runAtfScanAndDumpJson() throws Exception {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            UiAutomation uiAutomation = instrumentation.getUiAutomation();
            Context context = instrumentation.getTargetContext();
            Bundle args = InstrumentationRegistry.getArguments();
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            DisplayMetrics realMetrics = realDisplayMetrics(context, metrics);

            boolean scrollToEnd = parseBoolean(args.getString("scrollToEnd"), false);
            int maxScrolls = Math.max(1, parseInt(args.getString("maxScrolls"), 12));

            ImmutableSet<AccessibilityHierarchyCheck> checks =
                    AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
                            AccessibilityCheckPreset.PRERELEASE);

            //logs
            for (AccessibilityHierarchyCheck check : checks) {
                Log.i(TAG, "REGISTERED CHECK: " + check.getClass().getName());
            }

            File filesDir = context.getExternalFilesDir(null);
            if (filesDir == null) {
                throw new IllegalStateException("getExternalFilesDir(null) returned null; cannot write ATF output");
            }
            File shotsDir = prepareShotsDir(filesDir);

            List<AtfIssueRecord> merged = new ArrayList<>();
            Set<String> seenIssueKeys = new LinkedHashSet<>();
            int viewports = 0;
            int nextShotIndex = 1;
            int unchangedStreak = 0;

            long cumulativeScrollPx = 0L;
            Map<String, List<Rect>> previousPositionlessBounds = null;

            // maxScrolls = max scroll advances after the first viewport (max viewports = maxScrolls + 1).
            for (int pass = 0; pass <= maxScrolls; pass++) {
                settleBeforeCapture(uiAutomation, realMetrics, pass == 0);
                // Sample bounds on both sides of the screenshot and keep only the
                // rects that did not move: anything still animating cannot be
                // cropped truthfully from this bitmap.
                Map<String, List<Rect>> boundsBefore = snapshotBounds(uiAutomation);

                Map<String, List<Rect>> positionlessBounds = snapshotPositionlessBounds(uiAutomation);

                Bitmap screenshot = uiAutomation.takeScreenshot();
                Map<String, List<Rect>> capturedBounds = stableBounds(boundsBefore, snapshotBounds(uiAutomation));

                if (previousPositionlessBounds != null) {
                    long shift = measureVerticalShift(previousPositionlessBounds, positionlessBounds, realMetrics.heightPixels);
                    cumulativeScrollPx += shift;
                    Log.i(TAG, "Viewport " + (pass + 1) + " scroll shift: " + shift + "px (cumulative=" + cumulativeScrollPx + "px)");
                }
                previousPositionlessBounds = positionlessBounds;

                AccessibilityHierarchyAndroid hierarchy = buildHierarchy(uiAutomation, context, pass == 0);
                Bitmap contrastCopy = null;
                List<AccessibilityHierarchyCheckResult> batch;
                try {
                    // Contrast sampling is expensive (Blinkit first viewport). Keep the
                    // screen capture on pass 0 only; later viewports still run structural
                    // checks and stay fast after the near-full-page swipe.
                    if (pass == 0 && screenshot != null) {
                        Bitmap.Config config = screenshot.getConfig() != null
                                ? screenshot.getConfig() : Bitmap.Config.ARGB_8888;
                        contrastCopy = screenshot.copy(config, false);
                    }
                    batch = runChecks(hierarchy, checks, contrastCopy);
                    int minY = (pass == 0) ? 0 : (int) (realMetrics.heightPixels * NEW_BAND_TOP_FRACTION);

                    List<AtfIssueRecord> atfAdded = mergeNew(
                            seenIssueKeys, merged, batch, cumulativeScrollPx,
                            realMetrics.widthPixels, realMetrics.heightPixels);
                    int beforeNested = atfAdded.size();
                    atfAdded = dropNestedDuplicates(atfAdded, merged);
                    int afterNested = atfAdded.size();
                    for (AtfIssueRecord record : atfAdded) {
                        record.setViewport(pass);
                    }
                    Map<String, String> cropByElement = new HashMap<>();
                    nextShotIndex = attachCrops(screenshot, atfAdded, shotsDir, realMetrics,
                            nextShotIndex, capturedBounds, cropByElement);

                    // Custom: crop each finding in the same walk that flags it.
                    // Dedup then deletes crops that only dropped duplicates used.
                    ImmediateCropper cropper = new ImmediateCropper(
                            screenshot, shotsDir, realMetrics, capturedBounds, cropByElement, nextShotIndex);
                    List<AtfIssueRecord> alreadyKept = new ArrayList<>(merged);
                    alreadyKept.addAll(atfAdded);
                    List<AtfIssueRecord> customFindings = runCustomHierarchyChecks(hierarchy, cropper);
                    nextShotIndex = cropper.nextIndex();
                    List<AtfIssueRecord> customAdded = mergeCustomNew(
                            seenIssueKeys, alreadyKept, customFindings, cumulativeScrollPx);
                    int beforeCustomNested = customAdded.size();
                    customAdded = dropNestedDuplicates(customAdded, alreadyKept);
                    int afterCustomNested = customAdded.size();
                    deleteOrphanedCustomShots(customFindings, customAdded, alreadyKept, shotsDir);
                    for (AtfIssueRecord record : customAdded) {
                        record.setViewport(pass);
                    }

                    merged.addAll(atfAdded);
                    merged.addAll(customAdded);

                    viewports++;
                    Log.i(TAG, "Viewport " + viewports + ": " + batch.size() + " raw, "
                            + (atfAdded.size() + customAdded.size())
                            + " new unique (minY=" + minY
                            + ", nestedDropped=" + (beforeNested - afterNested)
                            + ", custom=" + customAdded.size()
                            + ", customNestedDropped=" + (beforeCustomNested - afterCustomNested) + ")");
                } finally {
                    if (contrastCopy != null && contrastCopy != screenshot && !contrastCopy.isRecycled()) {
                        contrastCopy.recycle();
                    }
                    if (screenshot != null && !screenshot.isRecycled()) {
                        screenshot.recycle();
                    }
                }

                if (!scrollToEnd || pass >= maxScrolls) {
                    break;
                }

                String fingerprint = contentFingerprint(uiAutomation, realMetrics);
                AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
                boolean scrollable = hasScrollable(root);
                if (root != null) {
                    root.recycle();
                }

                if (!scrollable) {
                    Log.i(TAG, "No scrollable node — stopping scroll loop");
                    break;
                }

                boolean moved = advanceScroll(uiAutomation, realMetrics, fingerprint);
                if (moved) {
                    unchangedStreak = 0;
                } else {
                    unchangedStreak++;
                    Log.i(TAG, "Scroll produced no new content (streak=" + unchangedStreak + ")");
                    if (unchangedStreak >= 2) {
                        Log.i(TAG, "Reached end of page — stopping scroll loop");
                        break;
                    }
                }
            }

            // ============================================================
            // CUSTOM CHECK — WCAG 1.3.4 Orientation
            // ============================================================

            AtfIssueRecord orientationResult =
                    runOrientationCheck(uiAutomation);

            if (orientationResult != null) {
                orientationResult.setViewport(0);
                merged.add(orientationResult);
            }

            String json = AtfResultJsonWriter.toJson(merged, metrics.density, viewports);
            File out = new File(filesDir, RESULT_FILE_NAME);
            writeToDevice(out.getAbsolutePath(), json);

            Log.i(TAG, "Accessibility scan complete: "
                    + merged.size()
                    + " results across "
                    + viewports
                    + " viewport(s). Written to "
                    + out.getAbsolutePath());
        }

        private List<AccessibilityHierarchyCheckResult> runChecks(
                AccessibilityHierarchyAndroid hierarchy,
                ImmutableSet<AccessibilityHierarchyCheck> checks,
                Bitmap screenshot) {
            Parameters parameters = new Parameters();
            if (screenshot != null) {
                parameters.putScreenCapture(new BitmapImage(screenshot));

                Context context =
                        InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()
                                .getApplicationContext();

                parameters.putOcrEngine(new MlKitOcrEngine(context));
            }
            List<AccessibilityHierarchyCheckResult> all = new ArrayList<>();
            for (AccessibilityHierarchyCheck check : checks) {

                Log.i(TAG, "RUNNING CHECK: " + check.getClass().getName());

                List<AccessibilityHierarchyCheckResult> results = check.runCheckOnHierarchy(hierarchy, null, parameters);

                Log.i(TAG, "CHECK RESULT COUNT: " + check.getClass().getSimpleName() + " = " + results.size());

                for (AccessibilityCheckResult result : results) {
                    Log.i("AtfScanTest",
                            "RESULT: type=" + result.getType()
                                    + ", message=" + result.getMessage());
                }

                all.addAll(results);
            }
            return all;
        }

        /**
         * Runs every registered per-viewport custom check on the same hierarchy
         * ATF just scanned. Each finding is cropped through {@code cropper}
         * before the check returns. Add new checks in {@link CustomHierarchyChecks}.
         */
        private List<AtfIssueRecord> runCustomHierarchyChecks(AccessibilityHierarchyAndroid hierarchy,
                                                              ViewportCropper cropper) {
            List<AtfIssueRecord> all = new ArrayList<>();
            for (CustomHierarchyCheck check : CustomHierarchyChecks.viewportChecks()) {
                Log.i(TAG, "RUNNING CUSTOM CHECK: " + check.getCheckName());
                List<AtfIssueRecord> results = check.evaluate(hierarchy, cropper);
                Log.i(TAG, "CUSTOM CHECK RESULT COUNT: " + check.getCheckName() + " = " + results.size());
                all.addAll(results);
            }
            return all;
        }

        private List<AtfIssueRecord> mergeNew(Set<String> seenIssueKeys,
                                              List<AtfIssueRecord> alreadyKept,
                                              List<AccessibilityHierarchyCheckResult> batch,
                                              long cumulativeScrollPx,
                                              int screenWidth,
                                              int screenHeight) {
            List<AtfIssueRecord> added = new ArrayList<>();
            for (AccessibilityHierarchyCheckResult result : batch) {
                AccessibilityCheckResultType type = result.getType();
                if (type == AccessibilityCheckResultType.NOT_RUN || type == AccessibilityCheckResultType.SUPPRESSED) {
                    continue;
                }
                ViewHierarchyElement el = result.getElement();
//                if (!hasUsableBounds(el) || isEdgeClippedSliver(el, screenWidth, screenHeight)) {
//                    continue;
//                }

                if (!hasUsableBounds(el)) {
                    continue;
                }
                // Two keys: raw screen position (catches sticky chrome, which never
                // moves) and document position (catches content that scrolled but is
                // the same widget on the page).
                String screenKey = issueKey(result, elementIdentity(el));
                String docKey = issueKey(result, documentIdentity(el, cumulativeScrollPx));
                if (seenIssueKeys.contains(screenKey) || seenIssueKeys.contains(docKey)) {
                    continue;
                }
                Rect docBounds = el != null ? documentBounds(el, cumulativeScrollPx) : null;
                if (hasSpatialDuplicate(result, el, docBounds, alreadyKept)
                        || hasSpatialDuplicate(result, el, docBounds, added)) {
                    continue;
                }
                seenIssueKeys.add(screenKey);
                seenIssueKeys.add(docKey);
                AtfIssueRecord record = new AtfIssueRecord(result, null);
                record.documentBounds = docBounds;
                added.add(record);
            }
            return added;
        }

        /**
         * Same identity + spatial collapse as {@link #mergeNew}, for custom
         * per-element findings that do not have an ATF result object.
         *
         * <p>Screen-position and document-position keys plus bounds IoU mean a
         * field that is still visible after a swipe is not reported again.
         */
        private List<AtfIssueRecord> mergeCustomNew(Set<String> seenIssueKeys,
                                                    List<AtfIssueRecord> alreadyKept,
                                                    List<AtfIssueRecord> candidates,
                                                    long cumulativeScrollPx) {
            List<AtfIssueRecord> added = new ArrayList<>();
            for (AtfIssueRecord record : candidates) {
                if (record == null) {
                    continue;
                }
                AccessibilityCheckResultType type = record.getResultType();
                if (type == AccessibilityCheckResultType.NOT_RUN || type == AccessibilityCheckResultType.SUPPRESSED) {
                    continue;
                }
                ViewHierarchyElement el = record.getElement();
                if (!hasUsableBounds(el)) {
                    continue;
                }
                String screenKey = customIssueKey(record, elementIdentity(el));
                String docKey = customIssueKey(record, documentIdentity(el, cumulativeScrollPx));
                if (seenIssueKeys.contains(screenKey) || seenIssueKeys.contains(docKey)) {
                    continue;
                }
                Rect docBounds = el != null ? documentBounds(el, cumulativeScrollPx) : null;
                if (hasSpatialDuplicate(record.getCheckClassName(), type, record.getResultIdValue(),
                        el, docBounds, alreadyKept)
                        || hasSpatialDuplicate(record.getCheckClassName(), type, record.getResultIdValue(),
                        el, docBounds, added)) {
                    continue;
                }
                seenIssueKeys.add(screenKey);
                seenIssueKeys.add(docKey);
                record.documentBounds = docBounds;
                added.add(record);
            }
            return added;
        }

        private boolean hasSpatialDuplicate(AccessibilityHierarchyCheckResult result,
                                            ViewHierarchyElement el,
                                            Rect docBounds,
                                            List<AtfIssueRecord> kept) {
            return hasSpatialDuplicate(
                    result.getSourceCheckClass().getSimpleName(),
                    result.getType(),
                    result.getResultId(),
                    el,
                    docBounds,
                    kept);
        }

        private boolean hasSpatialDuplicate(String check,
                                            AccessibilityCheckResultType type,
                                            int resultId,
                                            ViewHierarchyElement el,
                                            Rect docBounds,
                                            List<AtfIssueRecord> kept) {
            if (el == null || kept == null || kept.isEmpty()) {
                return false;
            }
            var atfBounds = el.getBoundsInScreen();
            String className = nullToEmpty(el.getClassName());
            for (AtfIssueRecord existing : kept) {
                if (!check.equals(existing.getCheckClassName())
                        || type != existing.getResultType()
                        || resultId != existing.getResultIdValue()) {
                    continue;
                }
                ViewHierarchyElement other = existing.getElement();
                if (other == null || !className.equals(nullToEmpty(other.getClassName()))) {
                    continue;
                }
                // Case 1: sticky chrome — still at the same screen position.
                var otherBounds = other.getBoundsInScreen();
                if (atfBounds != null && otherBounds != null
                        && intersectionOverUnion(
                        atfBounds.getLeft(), atfBounds.getTop(), atfBounds.getRight(), atfBounds.getBottom(),
                        otherBounds.getLeft(), otherBounds.getTop(), otherBounds.getRight(), otherBounds.getBottom())
                        >= SPATIAL_DEDUPE_IOU) {
                    return true;
                }
                // Case 2: scrolled content — same spot on the page once translated.
                if (docBounds != null && existing.documentBounds != null
                        && intersectionOverUnion(
                        docBounds.left, docBounds.top, docBounds.right, docBounds.bottom,
                        existing.documentBounds.left, existing.documentBounds.top,
                        existing.documentBounds.right, existing.documentBounds.bottom)
                        >= SPATIAL_DEDUPE_IOU) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Crops the failing widget with surrounding context and a red highlight.
         * Same element in this viewport reuses one PNG when several checks fire
         * on it ({@code cropByElement} is shared between the ATF pass and
         * immediate custom crops). ATF findings whose widget cannot be located
         * in the bounds sampled alongside the screenshot get no image — a
         * wrong crop is worse than a missing one.
         */
        private int attachCrops(Bitmap screenshot,
                                List<AtfIssueRecord> added,
                                File shotsDir,
                                DisplayMetrics metrics,
                                int nextShotIndex,
                                Map<String, List<Rect>> capturedBounds,
                                Map<String, String> cropByElement) {
            if (screenshot == null || added.isEmpty()) {
                return nextShotIndex;
            }
            if (cropByElement == null) {
                cropByElement = new HashMap<>();
            }
            Log.i(TAG, "Screenshot " + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " realDisplay=" + metrics.widthPixels + "x" + metrics.heightPixels
                    + " cropScale=" + screenshotScale(screenshot, metrics));
            int index = nextShotIndex;
            for (AtfIssueRecord record : added) {
                index = attachOneCrop(screenshot, record, shotsDir, metrics, index,
                        capturedBounds, cropByElement, false);
            }
            return index;
        }

        /**
         * Crops one finding. Custom checks pass {@code allowHierarchyFallback}
         * so the node's own bounds are used when the live snapshot missed it.
         */
        private int attachOneCrop(Bitmap screenshot,
                                  AtfIssueRecord record,
                                  File shotsDir,
                                  DisplayMetrics metrics,
                                  int nextShotIndex,
                                  Map<String, List<Rect>> capturedBounds,
                                  Map<String, String> cropByElement,
                                  boolean allowHierarchyFallback) {
            if (screenshot == null || record == null) {
                return nextShotIndex;
            }
            ViewHierarchyElement el = record.getElement();
            if (el == null) {
                return nextShotIndex;
            }
            String identity = elementIdentity(el);
            Rect live = resolveCapturedBounds(el, identity, capturedBounds);
            if (live == null && allowHierarchyFallback) {
                live = hierarchyScreenBounds(el);
            }
            if (live == null) {
                Log.w(TAG, "Skipping crop — widget not stable at capture time: " + identity);
                return nextShotIndex;
            }
            record.capturedBounds = live;
            String existing = cropByElement.get(identity);
            if (existing != null) {
                record.screenshotFile = existing;
                return nextShotIndex;
            }
            String file = cropElement(screenshot, live, shotsDir, metrics, nextShotIndex);
            if (file != null) {
                record.screenshotFile = file;
                cropByElement.put(identity, file);
                return nextShotIndex + 1;
            }
            return nextShotIndex;
        }

        private Rect hierarchyScreenBounds(ViewHierarchyElement el) {
            if (el == null) {
                return null;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return null;
            }
            Rect rect = new Rect(bounds.getLeft(), bounds.getTop(), bounds.getRight(), bounds.getBottom());
            if (rect.width() < MIN_VISIBLE_EDGE_PX || rect.height() < MIN_VISIBLE_EDGE_PX) {
                return null;
            }
            return rect;
        }

        /**
         * Deletes crop files written for custom findings that did not survive
         * identity / spatial / nested dedup, unless another kept issue still
         * points at the same PNG.
         */
        private void deleteOrphanedCustomShots(List<AtfIssueRecord> candidates,
                                               List<AtfIssueRecord> survivors,
                                               List<AtfIssueRecord> alsoKeep,
                                               File shotsDir) {
            Set<String> keep = new HashSet<>();
            collectShotNames(survivors, keep);
            collectShotNames(alsoKeep, keep);
            Set<String> produced = new HashSet<>();
            collectShotNames(candidates, produced);
            for (String name : produced) {
                if (keep.contains(name)) {
                    continue;
                }
                File file = new File(shotsDir, name);
                if (!file.exists()) {
                    continue;
                }
                if (file.delete()) {
                    Log.i(TAG, "Deleted orphaned custom crop " + name);
                } else {
                    Log.w(TAG, "Could not delete orphaned custom crop " + file.getAbsolutePath());
                }
            }
        }

        private static void collectShotNames(List<AtfIssueRecord> records, Set<String> out) {
            if (records == null) {
                return;
            }
            for (AtfIssueRecord record : records) {
                if (record == null || record.screenshotFile == null || record.screenshotFile.isBlank()) {
                    continue;
                }
                out.add(new File(record.screenshotFile).getName());
            }
        }

        /** Immediate crop used by custom checks while they still have the node. */
        private final class ImmediateCropper implements ViewportCropper {
            private final Bitmap screenshot;
            private final File shotsDir;
            private final DisplayMetrics metrics;
            private final Map<String, List<Rect>> capturedBounds;
            private final Map<String, String> cropByElement;
            private int nextShotIndex;

            ImmediateCropper(Bitmap screenshot,
                             File shotsDir,
                             DisplayMetrics metrics,
                             Map<String, List<Rect>> capturedBounds,
                             Map<String, String> cropByElement,
                             int nextShotIndex) {
                this.screenshot = screenshot;
                this.shotsDir = shotsDir;
                this.metrics = metrics;
                this.capturedBounds = capturedBounds;
                this.cropByElement = cropByElement;
                this.nextShotIndex = nextShotIndex;
            }

            @Override
            public void crop(AtfIssueRecord record) {
                nextShotIndex = attachOneCrop(screenshot, record, shotsDir, metrics,
                        nextShotIndex, capturedBounds, cropByElement, true);
            }

            int nextIndex() {
                return nextShotIndex;
            }
        }

        /**
         * Bounds for this widget as they were when the bitmap was taken. Null when
         * the widget was absent or moving, which is how a stale ATF rect used to
         * put a backpack behind the "ChargeCube" highlight.
         */
        private Rect resolveCapturedBounds(ViewHierarchyElement el,
                                           String identity,
                                           Map<String, List<Rect>> capturedBounds) {
            List<Rect> candidates = capturedBounds.get(identity);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Rect best = candidates.get(0);
            if (candidates.size() > 1) {
                var atf = el.getBoundsInScreen();
                int centerX = atf != null ? (atf.getLeft() + atf.getRight()) / 2 : 0;
                int centerY = atf != null ? (atf.getTop() + atf.getBottom()) / 2 : 0;
                long bestDistance = Long.MAX_VALUE;
                for (Rect candidate : candidates) {
                    long dx = candidate.centerX() - centerX;
                    long dy = candidate.centerY() - centerY;
                    long distance = dx * dx + dy * dy;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
            if (best.width() < MIN_VISIBLE_EDGE_PX || best.height() < MIN_VISIBLE_EDGE_PX) {
                return null;
            }
            return best;
        }

        private String cropElement(Bitmap full,
                                   Rect bounds,
                                   File shotsDir,
                                   DisplayMetrics metrics,
                                   int index) {
            if (full.isRecycled()) {
                return null;
            }
            // Bounds and the screenshot share screen-pixel X/Y. Never scale by
            // heightPixels — that value omits the nav bar, so scaleY > 1 and every
            // crop slides downward (C+7, back-button becoming the search icon).
            // Scale only when the bitmap width differs from the display width (WQHD vs FHD).
            float scale = screenshotScale(full, metrics);
            int elLeft = Math.round(bounds.left * scale);
            int elTop = Math.round(bounds.top * scale);
            int elRight = Math.round(bounds.right * scale);
            int elBottom = Math.round(bounds.bottom * scale);
            int extraX = Math.max(CROP_PADDING_PX, (MIN_CROP_WIDTH_PX - (elRight - elLeft)) / 2);
            int extraY = Math.max(CROP_PADDING_PX, (MIN_CROP_HEIGHT_PX - (elBottom - elTop)) / 2);
            int left = clamp(elLeft - extraX, 0, full.getWidth() - 1);
            int top = clamp(elTop - extraY, 0, full.getHeight() - 1);
            int right = clamp(elRight + extraX, left + 1, full.getWidth());
            int bottom = clamp(elBottom + extraY, top + 1, full.getHeight());
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0) {
                return null;
            }
            Bitmap crop = Bitmap.createBitmap(full, left, top, width, height);
            Bitmap marked = highlightElement(crop, elLeft - left, elTop - top, elRight - left, elBottom - top);
            String relative = SHOTS_DIR_NAME + "/" + String.format(Locale.US, "issue-%04d.png", index);
            File out = new File(shotsDir, String.format(Locale.US, "issue-%04d.png", index));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!marked.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    Log.w(TAG, "Failed to compress crop " + out.getName());
                    return null;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to write crop " + out.getAbsolutePath(), e);
                return null;
            } finally {
                if (marked != crop && !marked.isRecycled()) {
                    marked.recycle();
                }
                if (!crop.isRecycled()) {
                    crop.recycle();
                }
            }
            return relative;
        }

        private Bitmap highlightElement(Bitmap crop, int left, int top, int right, int bottom) {
            Bitmap.Config config = crop.getConfig() != null ? crop.getConfig() : Bitmap.Config.ARGB_8888;
            Bitmap marked = crop.copy(config, true);
            if (marked == null) {
                return crop;
            }
            Canvas canvas = new Canvas(marked);
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6f);
            paint.setColor(Color.RED);
            paint.setAntiAlias(true);
            int x1 = clamp(left, 0, marked.getWidth() - 1);
            int y1 = clamp(top, 0, marked.getHeight() - 1);
            int x2 = clamp(right, x1 + 1, marked.getWidth());
            int y2 = clamp(bottom, y1 + 1, marked.getHeight());
            canvas.drawRect(x1, y1, x2, y2, paint);
            return marked;
        }

        private File prepareShotsDir(File filesDir) {
            File shotsDir = new File(filesDir, SHOTS_DIR_NAME);
            if (shotsDir.exists()) {
                File[] existing = shotsDir.listFiles();
                if (existing != null) {
                    for (File file : existing) {
                        if (!file.delete()) {
                            Log.w(TAG, "Could not delete stale crop " + file.getAbsolutePath());
                        }
                    }
                }
            } else if (!shotsDir.mkdirs()) {
                throw new IllegalStateException("Could not create shots directory " + shotsDir.getAbsolutePath());
            }
            return shotsDir;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        /** Width-only scale. Height is not used — nav-bar mismatch must not stretch Y. */
        private static float screenshotScale(Bitmap screenshot, DisplayMetrics metrics) {
            if (screenshot == null || metrics.widthPixels <= 0) {
                return 1f;
            }
            float widthRatio = (float) screenshot.getWidth() / metrics.widthPixels;
            if (Math.abs(widthRatio - 1f) <= 0.02f) {
                return 1f;
            }
            return widthRatio;
        }

        /** Physical display size, including system bars — matches UiAutomation screenshots. */
        private static DisplayMetrics realDisplayMetrics(Context context, DisplayMetrics fallback) {
            DisplayMetrics real = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager != null ? windowManager.getDefaultDisplay() : null;
            if (display == null) {
                real.setTo(fallback);
                return real;
            }
            display.getRealMetrics(real);
            if (real.widthPixels <= 0 || real.heightPixels <= 0) {
                real.setTo(fallback);
            }
            Log.i(TAG, "Display metrics app=" + fallback.widthPixels + "x" + fallback.heightPixels
                    + " real=" + real.widthPixels + "x" + real.heightPixels);
            return real;
        }

        /**
         * Distinguishes every on-screen instance the way Accessibility Scanner does.
         * List rows often reuse the same resource id and label (e.g. every product
         * card's "500 g" weight picker), so resource/text/desc alone would collapse
         * dozens of findings into one. Quantized center keeps siblings distinct
         * while still matching sticky chrome that stays at the same screen spot
         * across scrolls.
         */
        private String elementIdentity(ViewHierarchyElement el) {
            if (el == null) {
                return "";
            }
            Rect bounds = null;
            var atfBounds = el.getBoundsInScreen();
            if (atfBounds != null) {
                bounds = new Rect(
                        atfBounds.getLeft(), atfBounds.getTop(),
                        atfBounds.getRight(), atfBounds.getBottom());
            }
            return buildIdentity(
                    nullToEmpty(el.getResourceName()),
                    nullToEmpty(el.getText()),
                    nullToEmpty(el.getContentDescription()),
                    nullToEmpty(el.getClassName()),
                    bounds);
        }

        private String buildIdentity(String resourceName, String text, String contentDescription,
                                     String className, Rect bounds) {
            String base = baseIdentity(resourceName, text, contentDescription, className);
            if (bounds == null || bounds.isEmpty()) {
                return base;
            }
            int qx = (bounds.centerX() / POSITION_BUCKET_PX) * POSITION_BUCKET_PX;
            int qy = (bounds.centerY() / POSITION_BUCKET_PX) * POSITION_BUCKET_PX;
            return base + "|@" + qx + "," + qy;
        }

        /**
         * Same check + same widget, ignoring the ATF message. Measured dp is in
         * the message and would otherwise keep two Add-to-cart buttons (33x33 vs
         * 33x34) as separate issues.
         */
        private String issueKey(AccessibilityHierarchyCheckResult result, String elementId) {
            return result.getSourceCheckClass().getSimpleName()
                    + "|" + result.getType()
                    + "|" + result.getResultId()
                    + "|" + elementId;
        }

        private String customIssueKey(AtfIssueRecord record, String elementId) {
            return record.getCheckClassName()
                    + "|" + record.getResultType()
                    + "|" + record.getResultIdValue()
                    + "|" + elementId;
        }

        private static float intersectionOverUnion(int aL, int aT, int aR, int aB,
                                                   int bL, int bT, int bR, int bB) {
            int left = Math.max(aL, bL);
            int top = Math.max(aT, bT);
            int right = Math.min(aR, bR);
            int bottom = Math.min(aB, bB);
            int inter = Math.max(0, right - left) * Math.max(0, bottom - top);
            int areaA = Math.max(0, aR - aL) * Math.max(0, aB - aT);
            int areaB = Math.max(0, bR - bL) * Math.max(0, bB - bT);
            int union = areaA + areaB - inter;
            if (union <= 0) {
                return 0f;
            }
            return inter / (float) union;
        }

        /**
         * Collapses two findings of the same check only when they cover essentially
         * the same widget (high bounds overlap + similar label). A parent card and
         * the distinct children inside it (image, title, price) are kept separately.
         */
        private List<AtfIssueRecord> dropNestedDuplicates(List<AtfIssueRecord> incoming,
                                                          List<AtfIssueRecord> alreadyKept) {
            List<AtfIssueRecord> survivors = new ArrayList<>();
            for (AtfIssueRecord candidate : incoming) {
                if (hasNestedMatch(candidate, alreadyKept)) {
                    continue;
                }
                boolean skipCandidate = false;
                List<AtfIssueRecord> next = new ArrayList<>(survivors.size());
                for (AtfIssueRecord existing : survivors) {
                    if (!isNestedSimilar(candidate, existing)) {
                        next.add(existing);
                        continue;
                    }
                    if (elementArea(candidate) < elementArea(existing)) {
                        continue;
                    }
                    skipCandidate = true;
                    next.add(existing);
                }
                if (!skipCandidate) {
                    next.add(candidate);
                }
                survivors = next;
            }
            return survivors;
        }

        private boolean hasNestedMatch(AtfIssueRecord candidate, List<AtfIssueRecord> kept) {
            for (AtfIssueRecord existing : kept) {
                if (isNestedSimilar(candidate, existing)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isNestedSimilar(AtfIssueRecord a, AtfIssueRecord b) {
            if (!sameFindingType(a, b)) {
                return false;
            }
            ViewHierarchyElement elA = a.getElement();
            ViewHierarchyElement elB = b.getElement();
            if (elA == null || elB == null) {
                return false;
            }
            // Containment alone is not a duplicate: a product card that wraps
            // an image, title, and price must keep each ATF finding. Collapse
            // only when the two rects are essentially the same widget.
            return nearlySameBounds(elA, elB) && similarSpeakable(elA, elB);
        }

        private boolean nearlySameBounds(ViewHierarchyElement a, ViewHierarchyElement b) {
            var ba = a.getBoundsInScreen();
            var bb = b.getBoundsInScreen();
            if (ba == null || bb == null) {
                return false;
            }
            return highOverlap(
                    ba.getLeft(), ba.getTop(), ba.getRight(), ba.getBottom(),
                    bb.getLeft(), bb.getTop(), bb.getRight(), bb.getBottom());
        }

        private boolean sameFindingType(AtfIssueRecord a, AtfIssueRecord b) {
            return a.getCheckClassName().equals(b.getCheckClassName())
                    && a.getResultType() == b.getResultType()
                    && a.getResultIdValue() == b.getResultIdValue();
        }

        private boolean hasUsableBounds(ViewHierarchyElement el) {
            if (el == null) {
                return true;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return false;
            }
            int width = bounds.getRight() - bounds.getLeft();
            int height = bounds.getBottom() - bounds.getTop();
            return width >= MIN_VISIBLE_EDGE_PX && height >= MIN_VISIBLE_EDGE_PX;
        }

        private boolean isEdgeClippedSliver(ViewHierarchyElement el, int screenWidth, int screenHeight) {
            if (el == null || screenWidth <= 0 || screenHeight <= 0) {
                return false;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return false;
            }
            int left = bounds.getLeft();
            int top = bounds.getTop();
            int right = bounds.getRight();
            int bottom = bounds.getBottom();
            int width = right - left;
            int height = bottom - top;
            if (left <= 2 && width < EDGE_SLIVER_PX) {
                return true;
            }
            if (right >= screenWidth - 2 && width < EDGE_SLIVER_PX) {
                return true;
            }
            if (top <= 2 && height < EDGE_SLIVER_PX) {
                return true;
            }
            return bottom >= screenHeight - 2 && height < EDGE_SLIVER_PX;
        }

        private int elementArea(AtfIssueRecord record) {
            ViewHierarchyElement el = record.getElement();
            if (el == null) {
                return 0;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return 0;
            }
            int width = Math.max(0, bounds.getRight() - bounds.getLeft());
            int height = Math.max(0, bounds.getBottom() - bounds.getTop());
            return width * height;
        }

        private static boolean highOverlap(int aL, int aT, int aR, int aB,
                                           int bL, int bT, int bR, int bB) {
            int left = Math.max(aL, bL);
            int top = Math.max(aT, bT);
            int right = Math.min(aR, bR);
            int bottom = Math.min(aB, bB);
            int inter = Math.max(0, right - left) * Math.max(0, bottom - top);
            int areaA = Math.max(0, aR - aL) * Math.max(0, aB - aT);
            int areaB = Math.max(0, bR - bL) * Math.max(0, bB - bT);
            int union = areaA + areaB - inter;
            return union > 0 && inter * 10 >= union * 7;
        }

        private boolean similarSpeakable(ViewHierarchyElement a, ViewHierarchyElement b) {
            String sa = speakable(a).toLowerCase(Locale.US);
            String sb = speakable(b).toLowerCase(Locale.US);
            // Do not treat two unlabeled nodes as "similar" — that collapsed distinct
            // QTalk icons that Accessibility Scanner reports separately. Unlabeled
            // wrappers are only collapsed when both labels are empty and the
            // caller already requires nearly-identical bounds.
            if (sa.isEmpty() && sb.isEmpty()) {
                return true;
            }
            if (sa.isEmpty() || sb.isEmpty()) {
                return false;
            }
            if (sa.equals(sb) || sa.startsWith(sb) || sb.startsWith(sa)) {
                return true;
            }
            int prefix = commonPrefixLength(sa, sb);
            int minLen = Math.min(sa.length(), sb.length());
            return prefix >= NESTED_LABEL_PREFIX_MIN && prefix * 10 >= minLen * 6;
        }

        private String speakable(ViewHierarchyElement el) {
            String desc = nullToEmpty(el.getContentDescription());
            return desc.isEmpty() ? nullToEmpty(el.getText()) : desc;
        }

        private static int commonPrefixLength(String a, String b) {
            int n = Math.min(a.length(), b.length());
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i;
        }

        private boolean inAcceptedBand(ViewHierarchyElement el, int minCenterY) {
            if (minCenterY <= 0) {
                return true;
            }
            var bounds = el.getBoundsInScreen();
            int centerY = (bounds.getTop() + bounds.getBottom()) / 2;
            return centerY >= minCenterY;
        }

        /**
         * Waits for the window list and for the visible labels to stop changing,
         * so the screenshot and the hierarchy describe the same frame. Amazon's
         * feed keeps repainting for a while after a scroll.
         */
        private void settleBeforeCapture(UiAutomation uiAutomation, DisplayMetrics metrics, boolean firstPass)
                throws InterruptedException {
            waitForWindows(uiAutomation, firstPass);
            String last = null;
            int stable = 0;
            int attempts = firstPass ? 20 : 12;
            for (int attempt = 0; attempt < attempts; attempt++) {
                String fingerprint = contentFingerprint(uiAutomation, metrics);
                if (fingerprint.equals(last)) {
                    stable++;
                    if (stable >= 2) {
                        return;
                    }
                } else {
                    stable = 0;
                }
                last = fingerprint;
                Thread.sleep(150);
            }
            Log.i(TAG, "Screen never fully settled — capturing anyway");
        }

        /** Every node's on-screen rect, keyed the same way as {@link #elementIdentity}. */
        private Map<String, List<Rect>> snapshotBounds(UiAutomation uiAutomation) {
            Map<String, List<Rect>> byIdentity = new HashMap<>();
            List<AccessibilityWindowInfo> windows = uiAutomation.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    try {
                        collectBounds(root, byIdentity);
                    } finally {
                        if (root != null) {
                            root.recycle();
                        }
                    }
                }
                return byIdentity;
            }
            AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
            try {
                collectBounds(root, byIdentity);
            } finally {
                if (root != null) {
                    root.recycle();
                }
            }
            return byIdentity;
        }

        private void collectBounds(AccessibilityNodeInfo node, Map<String, List<Rect>> byIdentity) {
            if (node == null) {
                return;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String identity = buildIdentity(
                    nullToEmpty(node.getViewIdResourceName()),
                    nullToEmpty(node.getText()),
                    nullToEmpty(node.getContentDescription()),
                    nullToEmpty(node.getClassName()),
                    bounds);
            List<Rect> rects = byIdentity.get(identity);
            if (rects == null) {
                rects = new ArrayList<>(1);
                byIdentity.put(identity, rects);
            }
            rects.add(bounds);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                try {
                    collectBounds(child, byIdentity);
                } finally {
                    if (child != null) {
                        child.recycle();
                    }
                }
            }
        }

        /** Rects present and identical in both samples, so they held still for the bitmap. */
        private Map<String, List<Rect>> stableBounds(Map<String, List<Rect>> before,
                                                     Map<String, List<Rect>> after) {
            Map<String, List<Rect>> stable = new HashMap<>();
            for (Map.Entry<String, List<Rect>> entry : before.entrySet()) {
                List<Rect> later = after.get(entry.getKey());
                if (later == null) {
                    continue;
                }
                List<Rect> kept = new ArrayList<>();
                for (Rect rect : entry.getValue()) {
                    if (later.contains(rect)) {
                        kept.add(rect);
                    }
                }
                if (!kept.isEmpty()) {
                    stable.put(entry.getKey(), kept);
                }
            }
            return stable;
        }

        private AccessibilityHierarchyAndroid buildHierarchy(UiAutomation uiAutomation, Context context,
                                                             boolean firstPass)
                throws InterruptedException {
            List<AccessibilityWindowInfo> windows = waitForWindows(uiAutomation, firstPass);
            if (windows != null && !windows.isEmpty()) {
                return AccessibilityHierarchyAndroid.newBuilder(windows, context).build();
            }
            AccessibilityNodeInfo root = waitForRoot(uiAutomation, firstPass);
            if (root != null) {
                return AccessibilityHierarchyAndroid.newBuilder(root, context).build();
            }
            throw new IllegalStateException(
                    "No accessibility windows or root node. The app must already be in the foreground.");
        }

        private List<AccessibilityWindowInfo> waitForWindows(UiAutomation uiAutomation, boolean firstPass)
                throws InterruptedException {
            int attempts = firstPass ? 15 : 4;
            List<AccessibilityWindowInfo> windows = null;
            for (int attempt = 0; attempt < attempts; attempt++) {
                windows = uiAutomation.getWindows();
                if (windows != null && !windows.isEmpty()) {
                    return windows;
                }
                Thread.sleep(firstPass ? 400 : 150);
            }
            return windows;
        }

        private AccessibilityNodeInfo waitForRoot(UiAutomation uiAutomation, boolean firstPass)
                throws InterruptedException {
            int attempts = firstPass ? 15 : 4;
            for (int attempt = 0; attempt < attempts; attempt++) {
                AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
                if (root != null) {
                    return root;
                }
                Thread.sleep(firstPass ? 400 : 150);
            }
            return null;
        }

        /**
         * Scroll the main vertical list nearly a full page. Prefer a large side
         * swipe first — ACTION_SCROLL_FORWARD on Amazon's hybrid feed often only
         * nudges half a viewport and leaves the same cards in the next capture.
         * Center swipes hit carousels / the video player, so gestures stay at the
         * left or right third of the screen.
         */
        private boolean advanceScroll(UiAutomation uiAutomation, DisplayMetrics metrics, String beforeFingerprint)
                throws Exception {
            swipeNearFullPage(uiAutomation, metrics, 0.18f);
            if (contentChanged(uiAutomation, metrics, beforeFingerprint)) {
                return true;
            }
            swipeNearFullPage(uiAutomation, metrics, 0.82f);
            if (contentChanged(uiAutomation, metrics, beforeFingerprint)) {
                return true;
            }
            if (scrollPrimaryVertical(uiAutomation, metrics)
                    && contentChanged(uiAutomation, metrics, beforeFingerprint)) {
                return true;
            }
            return false;
        }

        private boolean contentChanged(UiAutomation uiAutomation, DisplayMetrics metrics, String before)
                throws InterruptedException {
            waitUntilSettled(uiAutomation, metrics);
            return !contentFingerprint(uiAutomation, metrics).equals(before);
        }

        /**
         * Near-full-page swipe with ~20% overlap so rows at the fold are not
         * skipped, but previously scanned content does not dominate the next band.
         */
        private void swipeNearFullPage(UiAutomation uiAutomation, DisplayMetrics metrics, float xFraction)
                throws Exception {
            int x = Math.round(metrics.widthPixels * xFraction);
            int y1 = (int) (metrics.heightPixels * 0.85);
            int y2 = (int) (metrics.heightPixels * 0.32);
            String cmd = "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 650";
            Log.i(TAG, "Near-full-page swipe: " + cmd);
            ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(cmd);
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                in.readAllBytes();
            }
        }

        private boolean scrollPrimaryVertical(UiAutomation uiAutomation, DisplayMetrics metrics) {
            AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            AccessibilityNodeInfo scroller = null;
            try {
                scroller = findPrimaryVerticalScroller(root, metrics, null);
                if (scroller == null) {
                    return false;
                }
                boolean ok = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                Log.i(TAG, "ACTION_SCROLL_FORWARD on primary scroller: " + ok);
                return ok;
            } finally {
                if (scroller != null) {
                    scroller.recycle();
                }
                root.recycle();
            }
        }

        private AccessibilityNodeInfo findPrimaryVerticalScroller(AccessibilityNodeInfo node,
                                                                  DisplayMetrics metrics,
                                                                  AccessibilityNodeInfo best) {
            if (node == null) {
                return best;
            }
            if (node.isScrollable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int height = bounds.height();
                int width = bounds.width();
                if (height >= (int) (metrics.heightPixels * 0.35f) && height >= width * 0.4f) {
                    boolean better = best == null;
                    if (!better) {
                        Rect current = new Rect();
                        best.getBoundsInScreen(current);
                        better = height > current.height();
                    }
                    if (better) {
                        if (best != null) {
                            best.recycle();
                        }
                        best = AccessibilityNodeInfo.obtain(node);
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                try {
                    best = findPrimaryVerticalScroller(child, metrics, best);
                } finally {
                    if (child != null) {
                        child.recycle();
                    }
                }
            }
            return best;
        }

        private void waitUntilSettled(UiAutomation uiAutomation, DisplayMetrics metrics)
                throws InterruptedException {
            Thread.sleep(400);
            String last = null;
            for (int i = 0; i < 8; i++) {
                String fp = contentFingerprint(uiAutomation, metrics);
                if (fp.equals(last) && i >= 2) {
                    return;
                }
                last = fp;
                Thread.sleep(150);
            }
        }

        /** Lower-band labels only — sticky Amazon chrome must not hide a real scroll. */
        private String contentFingerprint(UiAutomation uiAutomation, DisplayMetrics metrics) {
            AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
            try {
                StringBuilder sb = new StringBuilder();
                int minY = (int) (metrics.heightPixels * 0.40f);
                collectLowerBand(root, sb, minY);
                return sb.toString();
            } finally {
                if (root != null) {
                    root.recycle();
                }
            }
        }

        private void collectLowerBand(AccessibilityNodeInfo node, StringBuilder sb, int minY) {
            if (node == null) {
                return;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int centerY = (bounds.top + bounds.bottom) / 2;
            if (centerY >= minY) {
                sb.append(node.getClassName()).append(':')
                        .append(node.getText()).append(':')
                        .append(node.getContentDescription()).append(';');
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                try {
                    collectLowerBand(child, sb, minY);
                } finally {
                    if (child != null) {
                        child.recycle();
                    }
                }
            }
        }

        private boolean hasScrollable(AccessibilityNodeInfo node) {
            if (node == null) {
                return false;
            }
            if (node.isScrollable()) {
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                try {
                    if (hasScrollable(child)) {
                        return true;
                    }
                } finally {
                    if (child != null) {
                        child.recycle();
                    }
                }
            }
            return false;
        }

        private void writeToDevice(String path, String content) throws Exception {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create directory " + parent.getAbsolutePath());
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        }

        private static String nullToEmpty(CharSequence cs) {
            return cs == null ? "" : cs.toString();
        }

        private static boolean parseBoolean(String raw, boolean fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return Boolean.parseBoolean(raw.trim());
        }

        private static int parseInt(String raw, int fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private Map<String, List<Rect>> snapshotPositionlessBounds(UiAutomation uiAutomation) {
            Map<String, List<Rect>> byIdentity = new HashMap<>();
            List<AccessibilityWindowInfo> windows = uiAutomation.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    try {
                        collectPositionlessBounds(root, byIdentity);
                    } finally {
                        if (root != null) root.recycle();
                    }
                }
                return byIdentity;
            }
            AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
            try {
                collectPositionlessBounds(root, byIdentity);
            } finally {
                if (root != null) root.recycle();
            }
            return byIdentity;
        }

        private void collectPositionlessBounds(AccessibilityNodeInfo node, Map<String, List<Rect>> byIdentity) {
            if (node == null) {
                return;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                String identity = baseIdentity(
                        nullToEmpty(node.getViewIdResourceName()),
                        nullToEmpty(node.getText()),
                        nullToEmpty(node.getContentDescription()),
                        nullToEmpty(node.getClassName()));
                byIdentity.computeIfAbsent(identity, k -> new ArrayList<>()).add(bounds);
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                try {
                    collectPositionlessBounds(child, byIdentity);
                } finally {
                    if (child != null) child.recycle();
                }
            }
        }

        /** The part of {@link #buildIdentity} that ignores screen position. */
        private static String baseIdentity(String resourceName, String text, String contentDescription, String className) {
            return resourceName + "|" + text + "|" + contentDescription + "|" + className;
        }

        /**
         * Estimates how far content moved vertically between two viewports by
         * matching elements whose resource id / text / description / class is
         * unique and unchanged on both sides, then taking the median shift of
         * those matches. Falls back to a nominal near-full-page estimate when too
         * few elements survive the scroll unambiguously (e.g. a full page change).
         */
        private long measureVerticalShift(Map<String, List<Rect>> before,
                                          Map<String, List<Rect>> after,
                                          int screenHeight) {
            List<Long> deltas = new ArrayList<>();
            for (Map.Entry<String, List<Rect>> entry : before.entrySet()) {
                List<Rect> beforeRects = entry.getValue();
                List<Rect> afterRects = after.get(entry.getKey());
                if (beforeRects.size() != 1 || afterRects == null || afterRects.size() != 1) {
                    continue; // ambiguous (repeated list items) — skip rather than risk a bad pairing
                }
                long delta = beforeRects.get(0).centerY() - afterRects.get(0).centerY();
                if (delta > MIN_MEANINGFUL_SHIFT_PX && delta < screenHeight) {
                    deltas.add(delta);
                }
            }
            if (deltas.size() < MIN_SHIFT_SAMPLES) {
                long nominal = Math.round(screenHeight * DEFAULT_SHIFT_FRACTION);
                Log.i(TAG, "Only " + deltas.size() + " unambiguous matches — using nominal shift " + nominal + "px");
                return nominal;
            }
            Collections.sort(deltas);
            return deltas.get(deltas.size() / 2);
        }

        /**
         * Same identity as {@link #elementIdentity}, but the position component is
         * expressed in document space (screen position + total scroll so far), so
         * an element that has merely moved on screen after a swipe still resolves
         * to the same key it had when first seen.
         */
        private String documentIdentity(ViewHierarchyElement el, long cumulativeScrollPx) {
            if (el == null) {
                return "";
            }
            Rect bounds = documentBounds(el, cumulativeScrollPx);
            return buildIdentity(
                    nullToEmpty(el.getResourceName()),
                    nullToEmpty(el.getText()),
                    nullToEmpty(el.getContentDescription()),
                    nullToEmpty(el.getClassName()),
                    bounds);
        }

        private Rect documentBounds(ViewHierarchyElement el, long cumulativeScrollPx) {
            var atfBounds = el.getBoundsInScreen();
            if (atfBounds == null) {
                return null;
            }
            Rect bounds = new Rect(atfBounds.getLeft(), atfBounds.getTop(), atfBounds.getRight(), atfBounds.getBottom());
            bounds.offset(0, (int) cumulativeScrollPx);
            return bounds;
        }


        //======================================CUSTOM CHECKS======================================
        // Per-viewport custom checks live in CustomHierarchyChecks and run inside
        // the scroll loop after ATF. Device-level checks stay here.

        //ORIENTATION
        /**
         * WCAG 1.3.4 Orientation check.
         *
         * The test forces the display into landscape and checks whether the
         * foreground application's accessibility window also adapts to landscape.
         *
         * If the display is landscape but the foreground app window remains
         * portrait, the application appears to restrict orientation.
         *
         * This is only a potential WCAG 1.3.4 issue because the automated
         * check cannot determine whether the orientation restriction is
         * essential to the functionality.
         */
        private AtfIssueRecord runOrientationCheck(
                UiAutomation uiAutomation) {

            int originalRotation =
                    getCurrentRotation(uiAutomation);

            Log.i(
                    TAG,
                    "Orientation check: original rotation = "
                            + originalRotation);

            try {

                // ------------------------------------------------------------
                // Step 1 — Force device/display into landscape.
                // ------------------------------------------------------------

                uiAutomation.setRotation(
                        ORIENTATION_TEST_ROTATION);

                waitForOrientationChange(uiAutomation);

                boolean landscapeApplied =
                        isLandscapeDisplay(uiAutomation);

                if (!landscapeApplied) {

                    Log.w(
                            TAG,
                            "Orientation check could not switch display to landscape");

                    return new AtfIssueRecord(
                            ORIENTATION_CHECK_NAME,
                            AtfScanTest.class.getName(),
                            AccessibilityCheckResultType.NOT_RUN,
                            11,
                            "Orientation check could not switch the display to landscape."
                    );
                }

                // ------------------------------------------------------------
                // Step 2 — Inspect foreground application window.
                // ------------------------------------------------------------

                Rect appWindowBounds =
                        getForegroundAppWindowBounds(uiAutomation);

                if (appWindowBounds == null
                        || appWindowBounds.isEmpty()) {

                    Log.w(
                            TAG,
                            "Orientation check could not determine foreground app bounds");

                    return new AtfIssueRecord(
                            ORIENTATION_CHECK_NAME,
                            AtfScanTest.class.getName(),
                            AccessibilityCheckResultType.NOT_RUN,
                            11,
                            "Orientation check could not determine the foreground application window bounds."
                    );
                }

                boolean appWindowLandscape =
                        appWindowBounds.width()
                                > appWindowBounds.height();

                Log.i(
                        TAG,
                        "Orientation check: displayLandscape="
                                + landscapeApplied
                                + ", appWindow="
                                + appWindowBounds.width()
                                + "x"
                                + appWindowBounds.height()
                                + ", appWindowLandscape="
                                + appWindowLandscape);

                // ------------------------------------------------------------
                // Step 3 — Create JSON result.
                // ------------------------------------------------------------

                if (!appWindowLandscape) {

                    Log.w(
                            TAG,
                            "WCAG 1.3.4 potential issue: "
                                    + "display is landscape but foreground app remains portrait");

                    AtfIssueRecord record =
                            new AtfIssueRecord(
                                    ORIENTATION_CHECK_NAME,
                                    AtfScanTest.class.getName(),
                                    AccessibilityCheckResultType.WARNING,
                                    11,
                                    "The foreground application did not adapt to landscape orientation. "
                                            + "This may indicate a WCAG 1.3.4 Orientation issue; "
                                            + "the automated check cannot determine whether the restriction is essential."
                            );

                    record.capturedBounds = appWindowBounds;

                    return record;
                }

                Log.i(
                        TAG,
                        "WCAG 1.3.4 orientation check passed: "
                                + "foreground app adapted to landscape");

                /*
                 * You said you want raw results of ALL types.
                 *
                 * Therefore we also retain the successful custom check as INFO.
                 */
                AtfIssueRecord record =
                        new AtfIssueRecord(
                                ORIENTATION_CHECK_NAME,
                                AtfScanTest.class.getName(),
                                AccessibilityCheckResultType.INFO,
                                11,
                                "The foreground application adapted to landscape orientation."
                        );

                record.capturedBounds =
                        appWindowBounds;

                return record;

            } finally {

                // ------------------------------------------------------------
                // Step 4 — ALWAYS restore original orientation.
                // ------------------------------------------------------------

                restoreRotation(
                        uiAutomation,
                        originalRotation);
            }
        }

        private int getCurrentRotation(UiAutomation uiAutomation) {

            AccessibilityNodeInfo root = null;

            try {
                root = uiAutomation.getRootInActiveWindow();

                if (root != null) {
                    Rect bounds = new Rect();
                    root.getBoundsInScreen(bounds);

                    if (bounds.width() > bounds.height()) {
                        return Surface.ROTATION_90;
                    }
                }

                // Fall back to the display rotation.
                Display display = getDefaultDisplay();

                if (display != null) {
                    return display.getRotation();
                }

            } finally {
                if (root != null) {
                    root.recycle();
                }
            }

            return Surface.ROTATION_0;
        }

        private Display getDefaultDisplay() {

            Context context =
                    InstrumentationRegistry.getInstrumentation()
                            .getTargetContext();

            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

            if (windowManager == null) {
                return null;
            }

            return windowManager.getDefaultDisplay();
        }

        private boolean isLandscapeDisplay(UiAutomation uiAutomation) {

            Display display = getDefaultDisplay();

            if (display != null) {
                int rotation = display.getRotation();

                return rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270;
            }

            return false;
        }

        /**
         * Returns the bounds of the most relevant foreground accessibility window.
         *
         * We prefer the active/focused window. If neither is available, the
         * largest accessibility window is used.
         */
        private Rect getForegroundAppWindowBounds(UiAutomation uiAutomation) {

            List<AccessibilityWindowInfo> windows = uiAutomation.getWindows();

            if (windows == null || windows.isEmpty()) {
                return null;
            }

            AccessibilityWindowInfo bestWindow = null;

            // ------------------------------------------------------------
            // First preference: active window.
            // ------------------------------------------------------------
            for (AccessibilityWindowInfo window : windows) {

                if (window == null) {
                    continue;
                }

                if (window.isActive()) {
                    bestWindow = window;
                    break;
                }
            }

            // ------------------------------------------------------------
            // Second preference: focused window.
            // ------------------------------------------------------------
            if (bestWindow == null) {

                for (AccessibilityWindowInfo window : windows) {

                    if (window == null) {
                        continue;
                    }

                    if (window.isFocused()) {
                        bestWindow = window;
                        break;
                    }
                }
            }

            // ------------------------------------------------------------
            // Final fallback: largest window.
            // ------------------------------------------------------------
            if (bestWindow == null) {

                int largestArea = 0;

                for (AccessibilityWindowInfo window : windows) {

                    if (window == null) {
                        continue;
                    }

                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);

                    int area = bounds.width() * bounds.height();

                    if (area > largestArea) {
                        largestArea = area;
                        bestWindow = window;
                    }
                }
            }

            if (bestWindow == null) {
                return null;
            }

            Rect result = new Rect();
            bestWindow.getBoundsInScreen(result);

            return result;
        }

        private void waitForOrientationChange(UiAutomation uiAutomation) {

            long deadline =
                    SystemClock.uptimeMillis() + ORIENTATION_SETTLE_MS;

            while (SystemClock.uptimeMillis() < deadline) {

                Display display = getDefaultDisplay();

                if (display != null) {

                    int rotation = display.getRotation();

                    if (rotation == Surface.ROTATION_90
                            || rotation == Surface.ROTATION_270) {

                        // Give the application a little more time to recreate/layout.
                        SystemClock.sleep(300);
                        return;
                    }
                }

                SystemClock.sleep(100);
            }

            // Final settling delay even if rotation detection timed out.
            SystemClock.sleep(300);
        }

        private void restoreRotation(UiAutomation uiAutomation,
                                     int originalRotation) {

            try {

                switch (originalRotation) {

                    case Surface.ROTATION_90:
                        uiAutomation.setRotation(
                                UiAutomation.ROTATION_FREEZE_90);
                        break;

                    case Surface.ROTATION_180:
                        uiAutomation.setRotation(
                                UiAutomation.ROTATION_FREEZE_180);
                        break;

                    case Surface.ROTATION_270:
                        uiAutomation.setRotation(
                                UiAutomation.ROTATION_FREEZE_270);
                        break;

                    case Surface.ROTATION_0:
                    default:
                        uiAutomation.setRotation(
                                UiAutomation.ROTATION_FREEZE_0);
                        break;
                }

                SystemClock.sleep(500);

                Log.i(TAG,
                        "Orientation restored to rotation=" + originalRotation);

            } catch (Exception e) {

                Log.e(TAG,
                        "Failed to restore original orientation",
                        e);
            }
        }
    }
