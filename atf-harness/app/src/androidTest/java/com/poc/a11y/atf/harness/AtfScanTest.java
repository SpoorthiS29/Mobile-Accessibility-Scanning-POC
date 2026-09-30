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
    import android.util.Base64;
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

    import java.io.ByteArrayOutputStream;
    import java.io.File;
    import java.io.FileOutputStream;
    import java.io.OutputStreamWriter;
    import java.io.Writer;
    import java.nio.charset.StandardCharsets;
    import java.util.ArrayList;
    import java.util.Collections;
    import java.util.HashMap;
    import java.util.LinkedHashSet;
    import java.util.List;
    import java.util.Locale;
    import java.util.Map;
    import java.util.Set;

    /**
     * Scans the already-visible app with real ATF. Scrolls nearly a full page with
     * a small overlap so rows are not skipped, and dedupes by check + element
     * identity (resource / label when present; quantized position for unlabeled
     * siblings) so the same widget is not reported twice after a swipe. Findings
     * still on screen in the next viewport are matched to the previous capture
     * by the measured scroll. The scroll distance is taken from the screenshot
     * when element tracking is ambiguous, so the same widget is not reported
     * again after a short nudge or a clipped row. Repeated labels stay distinct.
     * Sticky chrome that stays put is collapsed by bounds overlap.
     * Clipped / zero-area nodes and edge slivers are dropped.
     * A parent card and the distinct widgets inside it (image, title, price)
     * stay as separate issues; only near-identical bounds of the same check
     * are treated as the same widget.
     * Crops include surrounding context plus a red highlight on the failing widget,
     * positioned from bounds sampled next to the screenshot rather than from the
     * ATF snapshot, because Amazon's web feed repaints while the checks run.
     * Each crop is compressed in memory and stored on the finding as raw base64
     * PNG. No screenshot file is written on the device; the result JSON is the
     * only artifact, and it already contains those base64 strings.
     * Per-viewport custom checks (see {@link CustomHierarchyChecks}) run
     * after ATF on the same hierarchy, still inside the scroll loop. ATF
     * findings are cropped after the preset batch. Custom findings are
     * cropped in the same node walk that flags them. Dropped duplicates are
     * omitted from the JSON. Both lists are then appended to the merged result.
     * Once scanning finishes, the screen is scrolled back to the starting
     * viewport so later steps see the top of the page.
     *
     * <p>This class does not launch the app under test. The Spring Boot / Appium
     * side must already have the target UI in the foreground (physical USB device
     * or Sauce Labs virtual device) before {@code am instrument} starts.
     */
    @RunWith(AndroidJUnit4.class)
    public class AtfScanTest {

        private static final String TAG = "AtfScanTest";
        static final String RESULT_FILE_NAME = "atf-result.json";

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

        /**
         * A widget may miss the measured scroll by this many pixels and still be
         * the same element. Kept below a typical list-row pitch so the next
         * identical row is not swallowed.
         */
        private static final int SCROLL_MATCH_SLACK_PX = 64;

        /**
         * Same-sized widget may sit this far from its previous document position
         * when the label changed. A second widget this much farther away keeps
         * the two rows distinct.
         */
        private static final int DOCUMENT_CENTER_MATCH_PX = 48;
        private static final int DOCUMENT_CENTER_RIVAL_GAP_PX = 72;

        /** Context around the failing widget so the crop is recognizable. */
        private static final int CROP_PADDING_PX = 48;

        /** Small targets are expanded to at least this crop so the shot is not a sliver. */
        private static final int MIN_CROP_WIDTH_PX = 240;
        private static final int MIN_CROP_HEIGHT_PX = 160;

        /** Nodes thinner than this (px) are clipped at the fold and produce sliver crops. */
        private static final int MIN_VISIBLE_EDGE_PX = 2;

        /** Edge-hugging strips narrower than this are carousel leftovers, not real widgets. */
        private static final int EDGE_SLIVER_PX = 80;

        /**
         * Shared speakable prefix used only when two findings already have
         * nearly the same bounds (same widget, not a parent card vs a child).
         */
        private static final int NESTED_LABEL_PREFIX_MIN = 24;

        /** Matched pairs below this delta are treated as sticky chrome, not scroll. */
        private static final long MIN_MEANINGFUL_SHIFT_PX = 20;
        /**
         * Fallback estimate (fraction of screen height) when element tracking cannot
         * see the scroll. Matches the finger travel of the 0.85 → 0.35 swipe.
         */
        private static final float DEFAULT_SHIFT_FRACTION = 0.50f;

        //Orientation
        /** WCAG 1.3.4 Orientation custom check. */
        private static final String ORIENTATION_CHECK_NAME = "Orientation";

        /** Landscape rotation used to test whether the foreground app adapts. */
        private static final int ORIENTATION_TEST_ROTATION = UiAutomation.ROTATION_FREEZE_90;

        /** Time allowed for the foreground application to settle after rotation. */
        private static final long ORIENTATION_SETTLE_MS = 1200L;

        private static final int STABLE_BOUNDS_TOLERANCE_PX = 3;

        @Test
        public void runAtfScanAndDumpJson() throws Exception {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            UiAutomation uiAutomation = instrumentation.getUiAutomation();
            Context context = instrumentation.getTargetContext();
            Bundle args = InstrumentationRegistry.getArguments();
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            DisplayMetrics realMetrics = realDisplayMetrics(context, metrics);

            int scrollCount = Math.max(0, parseInt(args.getString("scrollCount"), 0));

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

            List<AtfIssueRecord> merged = new ArrayList<>();
            Set<String> seenIssueKeys = new LinkedHashSet<>();
            int viewports = 0;
            int unchangedStreak = 0;
            int scrollsPerformed = 0;
            String originFingerprint = null;

            long cumulativeScrollPx = 0L;
            Map<String, List<Rect>> previousPositionlessBounds = null;
            VisualColumn previousVisual = null;
            List<ElementSighting> previousSightings = new ArrayList<>();

            // scrollCount = max scroll advances after the first viewport (max viewports = scrollCount + 1).
            for (int pass = 0; pass <= scrollCount; pass++) {
                settleBeforeCapture(uiAutomation, realMetrics, pass == 0);
                if (pass == 0) {
                    originFingerprint = contentFingerprint(uiAutomation, realMetrics);
                }
                // Sample bounds on both sides of the screenshot and keep only the
                // rects that did not move: anything still animating cannot be
                // cropped truthfully from this bitmap.
                Map<String, List<Rect>> boundsBefore = snapshotBounds(uiAutomation);

                Map<String, List<Rect>> positionlessBounds = snapshotPositionlessBounds(uiAutomation);

                Bitmap screenshot = uiAutomation.takeScreenshot();
                Map<String, List<Rect>> capturedBounds = stableBounds(boundsBefore, snapshotBounds(uiAutomation));
                VisualColumn visual = sampleVisual(screenshot, realMetrics);

                long shiftThisPass = 0L;
                if (previousPositionlessBounds != null) {
                    ShiftEstimate tracked = measureVerticalShift(
                            previousPositionlessBounds, positionlessBounds,
                            realMetrics.widthPixels, realMetrics.heightPixels);
                    Long visualShift = measureVisualShift(
                            previousVisual, visual, realMetrics.heightPixels);
                    shiftThisPass = chooseScrollShift(
                            tracked, visualShift, realMetrics.heightPixels, pass + 1);
                    cumulativeScrollPx += shiftThisPass;
                    Log.i(TAG, "Viewport " + (pass + 1) + " cumulative scroll "
                            + cumulativeScrollPx + "px");
                }
                previousVisual = visual;
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
                            realMetrics.widthPixels, realMetrics.heightPixels,
                            previousSightings, shiftThisPass);
                    int beforeNested = atfAdded.size();
                    atfAdded = dropNestedDuplicates(atfAdded, merged);
                    int afterNested = atfAdded.size();
                    for (AtfIssueRecord record : atfAdded) {
                        record.setViewport(pass);
                    }
                    Map<String, String> cropByElement = new HashMap<>();
                    attachCrops(screenshot, atfAdded, realMetrics, capturedBounds, cropByElement);

                    // Custom: crop each finding in the same walk that flags it.
                    // Dropped duplicates are left out of the JSON, so their
                    // in-memory crops are never written anywhere.
                    ImmediateCropper cropper = new ImmediateCropper(
                            screenshot, realMetrics, capturedBounds, cropByElement);
                    List<AtfIssueRecord> alreadyKept = new ArrayList<>(merged);
                    alreadyKept.addAll(atfAdded);
                    List<AtfIssueRecord> customFindings = runCustomHierarchyChecks(hierarchy, cropper);
                    List<AtfIssueRecord> customAdded = mergeCustomNew(
                            seenIssueKeys, alreadyKept, customFindings, cumulativeScrollPx,
                            previousSightings, shiftThisPass, realMetrics.heightPixels);
                    List<ElementSighting> thisPassSightings = sightingsFromResults(batch);
                    thisPassSightings.addAll(sightingsFromRecords(customFindings));
                    previousSightings = thisPassSightings;
                    int beforeCustomNested = customAdded.size();
                    customAdded = dropNestedDuplicates(customAdded, alreadyKept);
                    int afterCustomNested = customAdded.size();
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

                if (pass >= scrollCount) {
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
                    scrollsPerformed++;
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

            try {
                AtfIssueRecord orientationResult = runOrientationCheck(uiAutomation);

                if (orientationResult != null) {
                    orientationResult.setViewport(0);
                    merged.add(orientationResult);
                }
            } finally {
                scrollBackToTop(uiAutomation, realDisplayMetrics(context, metrics),
                        originFingerprint, scrollsPerformed);
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
                                              int screenHeight,
                                              List<ElementSighting> previousSightings,
                                              long shiftThisPass) {
            List<AtfIssueRecord> added = new ArrayList<>();
            int overlapDropped = 0;
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
                String checkName = result.getSourceCheckClass() == null
                        ? ""
                        : result.getSourceCheckClass().getSimpleName();
                if (seenInPreviousViewport(checkName, type, el,
                        previousSightings, shiftThisPass, screenHeight)) {
                    overlapDropped++;
                    continue;
                }
                // Docked header: first viewport had it below other content, later
                // viewports pin it to the top. Remember that screen slot so the
                // following viewport matches the screen key instead of reporting
                // the same control again.
                if (isPinnedStickyDuplicate(checkName, type, el,
                        previousSightings, shiftThisPass, screenHeight)) {
                    seenIssueKeys.add(screenKey);
                    overlapDropped++;
                    continue;
                }
                Rect docBounds = el != null ? documentBounds(el, cumulativeScrollPx) : null;
                if (hasSpatialDuplicate(result, el, docBounds, alreadyKept, true)
                        || hasSpatialDuplicate(result, el, docBounds, added, false)) {
                    continue;
                }
                seenIssueKeys.add(screenKey);
                seenIssueKeys.add(docKey);
                AtfIssueRecord record = new AtfIssueRecord(result, null);
                record.documentBounds = docBounds;
                added.add(record);
            }
            if (overlapDropped > 0) {
                Log.i(TAG, "Dropped " + overlapDropped
                        + " finding(s) already seen in the previous viewport");
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
                                                    long cumulativeScrollPx,
                                                    List<ElementSighting> previousSightings,
                                                    long shiftThisPass,
                                                    int screenHeight) {
            List<AtfIssueRecord> added = new ArrayList<>();
            int overlapDropped = 0;
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
                if (seenInPreviousViewport(record.getCheckClassName(), type, el,
                        previousSightings, shiftThisPass, screenHeight)) {
                    overlapDropped++;
                    continue;
                }
                if (isPinnedStickyDuplicate(record.getCheckClassName(), type, el,
                        previousSightings, shiftThisPass, screenHeight)) {
                    seenIssueKeys.add(screenKey);
                    overlapDropped++;
                    continue;
                }
                Rect docBounds = el != null ? documentBounds(el, cumulativeScrollPx) : null;
                if (hasSpatialDuplicate(record.getCheckClassName(), type,
                        el, docBounds, alreadyKept, true)
                        || hasSpatialDuplicate(record.getCheckClassName(), type,
                        el, docBounds, added, false)) {
                    continue;
                }
                seenIssueKeys.add(screenKey);
                seenIssueKeys.add(docKey);
                record.documentBounds = docBounds;
                added.add(record);
            }
            if (overlapDropped > 0) {
                Log.i(TAG, "Dropped " + overlapDropped
                        + " custom finding(s) already seen in the previous viewport");
            }
            return added;
        }

        private boolean hasSpatialDuplicate(AccessibilityHierarchyCheckResult result,
                                            ViewHierarchyElement el,
                                            Rect docBounds,
                                            List<AtfIssueRecord> kept,
                                            boolean acrossViewports) {
            return hasSpatialDuplicate(
                    result.getSourceCheckClass().getSimpleName(),
                    result.getType(),
                    el,
                    docBounds,
                    kept,
                    acrossViewports);
        }

        private boolean hasSpatialDuplicate(String check,
                                            AccessibilityCheckResultType type,
                                            ViewHierarchyElement el,
                                            Rect docBounds,
                                            List<AtfIssueRecord> kept,
                                            boolean acrossViewports) {
            if (el == null || kept == null || kept.isEmpty()) {
                return false;
            }
            var atfBounds = el.getBoundsInScreen();
            String className = nullToEmpty(el.getClassName());
            int bestDocDy = Integer.MAX_VALUE;
            int secondDocDy = Integer.MAX_VALUE;
            for (AtfIssueRecord existing : kept) {
                if (!check.equals(existing.getCheckClassName())
                        || type != existing.getResultType()) {
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
                if (docBounds != null && existing.documentBounds != null) {
                    if (intersectionOverUnion(
                            docBounds.left, docBounds.top, docBounds.right, docBounds.bottom,
                            existing.documentBounds.left, existing.documentBounds.top,
                            existing.documentBounds.right, existing.documentBounds.bottom)
                            >= SPATIAL_DEDUPE_IOU) {
                        return true;
                    }
                    // Small widgets miss the IoU test when the scroll estimate is
                    // off by a few dozen pixels. Same label and a close center is
                    // still the same element; the slack stays under a row pitch.
                    if (sameStableIdentity(el, other)
                            && Math.abs(docBounds.centerX() - existing.documentBounds.centerX()) <= SCROLL_MATCH_SLACK_PX
                            && Math.abs(docBounds.centerY() - existing.documentBounds.centerY()) <= SCROLL_MATCH_SLACK_PX) {
                        return true;
                    }
                    // Same-sized widget whose label changed between captures
                    // (clipped text, a live price). Only across viewports, and
                    // only when no neighboring row is an equally good fit.
                    if (acrossViewports
                            && similarSpan(docBounds.width(), existing.documentBounds.width())
                            && similarSpan(docBounds.height(), existing.documentBounds.height())
                            && Math.abs(docBounds.centerX() - existing.documentBounds.centerX())
                            <= SCROLL_MATCH_SLACK_PX) {
                        int dy = Math.abs(docBounds.centerY() - existing.documentBounds.centerY());
                        if (dy < bestDocDy) {
                            secondDocDy = bestDocDy;
                            bestDocDy = dy;
                        } else if (dy < secondDocDy) {
                            secondDocDy = dy;
                        }
                    }
                }
            }
            return acrossViewports
                    && bestDocDy <= DOCUMENT_CENTER_MATCH_PX
                    && secondDocDy >= bestDocDy + DOCUMENT_CENTER_RIVAL_GAP_PX;
        }

        /**
         * Crops the failing widget with surrounding context and a red highlight.
         * Same element in this viewport reuses one base64 PNG when several checks
         * fire on it ({@code cropByElement} is shared between the ATF pass and
         * immediate custom crops). ATF findings whose widget cannot be located
         * in the bounds sampled alongside the screenshot get no image — a
         * wrong crop is worse than a missing one.
         */
        private void attachCrops(Bitmap screenshot,
                                 List<AtfIssueRecord> added,
                                 DisplayMetrics metrics,
                                 Map<String, List<Rect>> capturedBounds,
                                 Map<String, String> cropByElement) {
            if (screenshot == null || added.isEmpty()) {
                return;
            }
            if (cropByElement == null) {
                cropByElement = new HashMap<>();
            }
            Log.i(TAG, "Screenshot " + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " realDisplay=" + metrics.widthPixels + "x" + metrics.heightPixels
                    + " cropScale=" + screenshotScale(screenshot, metrics));
            for (AtfIssueRecord record : added) {
                attachOneCrop(screenshot, record, metrics, capturedBounds, cropByElement, false);
            }
        }

        /**
         * Crops one finding. Custom checks pass {@code allowHierarchyFallback}
         * so the node's own bounds are used when the live snapshot missed it.
         */
        private void attachOneCrop(Bitmap screenshot,
                                   AtfIssueRecord record,
                                   DisplayMetrics metrics,
                                   Map<String, List<Rect>> capturedBounds,
                                   Map<String, String> cropByElement,
                                   boolean allowHierarchyFallback) {
            if (screenshot == null || record == null) {
                return;
            }
            ViewHierarchyElement el = record.getElement();
            if (el == null) {
                return;
            }
            String identity = elementIdentity(el);
            Rect live = resolveCapturedBounds(el, identity, capturedBounds);
            if (live == null && allowHierarchyFallback) {
                live = hierarchyScreenBounds(el);
            }
            if (live == null) {
                Log.w(TAG, "Skipping crop — widget not stable at capture time: " + identity);
                return;
            }
            record.capturedBounds = live;
            String existing = cropByElement.get(identity);
            if (existing != null) {
                record.screenshot = existing;
                return;
            }
            String encoded = cropElement(screenshot, live, metrics);
            if (encoded != null) {
                record.screenshot = encoded;
                cropByElement.put(identity, encoded);
            }
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

        /** Immediate crop used by custom checks while they still have the node. */
        private final class ImmediateCropper implements ViewportCropper {
            private final Bitmap screenshot;
            private final DisplayMetrics metrics;
            private final Map<String, List<Rect>> capturedBounds;
            private final Map<String, String> cropByElement;

            ImmediateCropper(Bitmap screenshot,
                             DisplayMetrics metrics,
                             Map<String, List<Rect>> capturedBounds,
                             Map<String, String> cropByElement) {
                this.screenshot = screenshot;
                this.metrics = metrics;
                this.capturedBounds = capturedBounds;
                this.cropByElement = cropByElement;
            }

            @Override
            public void crop(AtfIssueRecord record) {
                attachOneCrop(screenshot, record, metrics, capturedBounds, cropByElement, true);
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

        /**
         * Crops and highlights one widget, then returns raw base64 PNG.
         * Nothing is written to disk.
         */
        private String cropElement(Bitmap full,
                                   Rect bounds,
                                   DisplayMetrics metrics) {
            if (full == null || full.isRecycled() || bounds == null || bounds.isEmpty()) {
                return null;
            }

            // Bounds and screenshot share screen-pixel X/Y.
            // Scale only when screenshot width differs from display width.
            float scale = screenshotScale(full, metrics);

            int left = Math.round(bounds.left * scale);
            int top = Math.round(bounds.top * scale);
            int right = Math.round(bounds.right * scale);
            int bottom = Math.round(bounds.bottom * scale);

            // Clamp the element bounds to the screenshot.
            left = clamp(left, 0, full.getWidth() - 1);
            top = clamp(top, 0, full.getHeight() - 1);
            right = clamp(right, left + 1, full.getWidth());
            bottom = clamp(bottom, top + 1, full.getHeight());

            int width = right - left;
            int height = bottom - top;

            if (width <= 0 || height <= 0) {
                return null;
            }

            // Crop ONLY the element with the accessibility issue.
            // No padding, no minimum crop size, no neighbouring elements.
            Bitmap crop = Bitmap.createBitmap(
                    full,
                    left,
                    top,
                    width,
                    height
            );

            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                if (!crop.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                    Log.w(TAG, "Failed to compress crop");
                    return null;
                }

                return Base64.encodeToString(
                        buffer.toByteArray(),
                        Base64.NO_WRAP
                );

            } catch (Exception e) {
                Log.w(TAG, "Failed to encode crop as base64", e);
                return null;

            } finally {
                if (!crop.isRecycled()) {
                    crop.recycle();
                }
            }
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
         * Same check + same widget. resultId is omitted: ATF assigns a new id
         * when a sticky control is measured again after a scroll, and that was
         * keeping the second snapshot.
         */
        private String issueKey(AccessibilityHierarchyCheckResult result, String elementId) {
            return result.getSourceCheckClass().getSimpleName()
                    + "|" + result.getType()
                    + "|" + elementId;
        }

        private String customIssueKey(AtfIssueRecord record, String elementId) {
            return record.getCheckClassName()
                    + "|" + record.getResultType()
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
                    && a.getResultType() == b.getResultType();
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

        private Map<String, List<Rect>> stableBounds(
                Map<String, List<Rect>> before,
                Map<String, List<Rect>> after) {

            Map<String, List<Rect>> stable = new HashMap<>();

            for (Map.Entry<String, List<Rect>> entry : before.entrySet()) {
                List<Rect> later = after.get(entry.getKey());

                if (later == null || later.isEmpty()) {
                    continue;
                }

                List<Rect> kept = new ArrayList<>();

                for (Rect beforeRect : entry.getValue()) {

                    Rect best = null;
                    long bestDistance = Long.MAX_VALUE;

                    for (Rect afterRect : later) {

                        if (Math.abs(beforeRect.left - afterRect.left)
                                > STABLE_BOUNDS_TOLERANCE_PX
                                || Math.abs(beforeRect.top - afterRect.top)
                                > STABLE_BOUNDS_TOLERANCE_PX
                                || Math.abs(beforeRect.right - afterRect.right)
                                > STABLE_BOUNDS_TOLERANCE_PX
                                || Math.abs(beforeRect.bottom - afterRect.bottom)
                                > STABLE_BOUNDS_TOLERANCE_PX) {
                            continue;
                        }

                        long distance =
                                Math.abs(beforeRect.left - afterRect.left)
                                        + Math.abs(beforeRect.top - afterRect.top)
                                        + Math.abs(beforeRect.right - afterRect.right)
                                        + Math.abs(beforeRect.bottom - afterRect.bottom);

                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = afterRect;
                        }
                    }

                    if (best != null) {
                        // Use the bounds from the screenshot's time window.
                        // Prefer the after-snapshot bounds because they are closest
                        // to the screenshot capture.
                        kept.add(new Rect(best));
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
            int y2 = (int) (metrics.heightPixels * 0.35);
            String cmd = "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 650";
            Log.i(TAG, "Near-full-page swipe: " + cmd);
            ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(cmd);
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                in.readAllBytes();
            }
        }

        /**
         * Undoes the scan's downward swipes so the app is back at the viewport
         * where scanning started. Stops early when that fingerprint returns, or
         * when further upward swipes no longer move the page.
         */
        private void scrollBackToTop(UiAutomation uiAutomation, DisplayMetrics metrics,
                                     String originFingerprint, int scrollsPerformed) {
            if (scrollsPerformed <= 0) {
                return;
            }
            int maxAttempts = Math.max(scrollsPerformed + 2, Math.min(scrollsPerformed * 2, 30));
            Log.i(TAG, "Scrolling back to top after " + scrollsPerformed
                    + " advance(s), maxAttempts=" + maxAttempts);
            try {
                int unchangedStreak = 0;
                for (int attempt = 0; attempt < maxAttempts; attempt++) {
                    String before = contentFingerprint(uiAutomation, metrics);
                    if (originFingerprint != null && originFingerprint.equals(before)) {
                        Log.i(TAG, "Already at the starting viewport");
                        return;
                    }
                    boolean moved = retreatScroll(uiAutomation, metrics, before);
                    if (!moved) {
                        unchangedStreak++;
                        Log.i(TAG, "Scroll toward top produced no new content (streak=" + unchangedStreak + ")");
                        if (unchangedStreak >= 2) {
                            Log.i(TAG, "Reached top of page");
                            return;
                        }
                        continue;
                    }
                    unchangedStreak = 0;
                    if (originFingerprint != null
                            && originFingerprint.equals(contentFingerprint(uiAutomation, metrics))) {
                        Log.i(TAG, "Returned to the starting viewport");
                        return;
                    }
                }
                Log.i(TAG, "Stopped scrolling toward top after " + maxAttempts + " attempt(s)");
            } catch (Exception e) {
                Log.w(TAG, "Could not scroll back to top: " + e.getMessage());
            }
        }

        /** Mirror of {@link #advanceScroll}: move content toward the top of the page. */
        private boolean retreatScroll(UiAutomation uiAutomation, DisplayMetrics metrics, String beforeFingerprint)
                throws Exception {
            swipeTowardTop(uiAutomation, metrics, 0.18f);
            if (contentChanged(uiAutomation, metrics, beforeFingerprint)) {
                return true;
            }
            swipeTowardTop(uiAutomation, metrics, 0.82f);
            if (contentChanged(uiAutomation, metrics, beforeFingerprint)) {
                return true;
            }
            return scrollPrimaryVerticalBackward(uiAutomation, metrics)
                    && contentChanged(uiAutomation, metrics, beforeFingerprint);
        }

        /** Finger moves down so the page content scrolls toward the top. */
        private void swipeTowardTop(UiAutomation uiAutomation, DisplayMetrics metrics, float xFraction)
                throws Exception {
            int x = Math.round(metrics.widthPixels * xFraction);
            int y1 = (int) (metrics.heightPixels * 0.35);
            int y2 = (int) (metrics.heightPixels * 0.85);
            String cmd = "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 650";
            Log.i(TAG, "Scroll-to-top swipe: " + cmd);
            ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(cmd);
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                in.readAllBytes();
            }
        }

        private boolean scrollPrimaryVerticalBackward(UiAutomation uiAutomation, DisplayMetrics metrics) {
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
                boolean ok = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                Log.i(TAG, "ACTION_SCROLL_BACKWARD on primary scroller: " + ok);
                return ok;
            } finally {
                if (scroller != null) {
                    scroller.recycle();
                }
                root.recycle();
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
            return normalizeIdentityText(resourceName) + "|"
                    + normalizeIdentityText(text) + "|"
                    + normalizeIdentityText(contentDescription) + "|"
                    + nullToEmpty(className).trim();
        }

        /** Trim and collapse whitespace so a repaint does not look like a new widget. */
        private static String normalizeIdentityText(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder(value.length());
            boolean pendingSpace = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isWhitespace(c)) {
                    pendingSpace = out.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
            }
            return out.toString();
        }

        /**
         * One finding as it appeared on screen in a viewport. The next viewport
         * uses these to drop the same widget when it is still visible after the swipe.
         */
        private static final class ElementSighting {
            final String check;
            final AccessibilityCheckResultType type;
            final int resultId;
            final String className;
            final String resourceName;
            final String text;
            final String contentDescription;
            final int centerX;
            final int centerY;
            final int width;
            final int height;

            ElementSighting(String check,
                            AccessibilityCheckResultType type,
                            int resultId,
                            String className,
                            String resourceName,
                            String text,
                            String contentDescription,
                            int centerX,
                            int centerY,
                            int width,
                            int height) {
                this.check = check;
                this.type = type;
                this.resultId = resultId;
                this.className = className;
                this.resourceName = resourceName;
                this.text = text;
                this.contentDescription = contentDescription;
                this.centerX = centerX;
                this.centerY = centerY;
                this.width = width;
                this.height = height;
            }
        }

        private List<ElementSighting> sightingsFromResults(List<AccessibilityHierarchyCheckResult> batch) {
            List<ElementSighting> sightings = new ArrayList<>();
            if (batch == null) {
                return sightings;
            }
            for (AccessibilityHierarchyCheckResult result : batch) {
                AccessibilityCheckResultType type = result.getType();
                if (type == AccessibilityCheckResultType.NOT_RUN
                        || type == AccessibilityCheckResultType.SUPPRESSED
                        || result.getSourceCheckClass() == null) {
                    continue;
                }
                ElementSighting sighting = sightingOf(
                        result.getSourceCheckClass().getSimpleName(),
                        type,
                        result.getResultId(),
                        result.getElement());
                if (sighting != null) {
                    sightings.add(sighting);
                }
            }
            return sightings;
        }

        private List<ElementSighting> sightingsFromRecords(List<AtfIssueRecord> records) {
            List<ElementSighting> sightings = new ArrayList<>();
            if (records == null) {
                return sightings;
            }
            for (AtfIssueRecord record : records) {
                if (record == null) {
                    continue;
                }
                AccessibilityCheckResultType type = record.getResultType();
                if (type == AccessibilityCheckResultType.NOT_RUN || type == AccessibilityCheckResultType.SUPPRESSED) {
                    continue;
                }
                ElementSighting sighting = sightingOf(
                        record.getCheckClassName(),
                        type,
                        record.getResultIdValue(),
                        record.getElement());
                if (sighting != null) {
                    sightings.add(sighting);
                }
            }
            return sightings;
        }

        private ElementSighting sightingOf(String check,
                                           AccessibilityCheckResultType type,
                                           int resultId,
                                           ViewHierarchyElement el) {
            if (el == null || !hasUsableBounds(el)) {
                return null;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return null;
            }
            return new ElementSighting(
                    check == null ? "" : check,
                    type,
                    resultId,
                    nullToEmpty(el.getClassName()),
                    nullToEmpty(el.getResourceName()),
                    nullToEmpty(el.getText()),
                    nullToEmpty(el.getContentDescription()),
                    (bounds.getLeft() + bounds.getRight()) / 2,
                    (bounds.getTop() + bounds.getBottom()) / 2,
                    bounds.getRight() - bounds.getLeft(),
                    bounds.getBottom() - bounds.getTop());
        }

        /**
         * True when this finding is a widget that was already on screen in the
         * previous viewport and moved with the scroll. Repeated labels stay
         * distinct: the match has to land on the position that widget moved to,
         * not on a sibling row that reused the same slot.
         */
        private boolean seenInPreviousViewport(String check,
                                              AccessibilityCheckResultType type,
                                              ViewHierarchyElement el,
                                              List<ElementSighting> previousSightings,
                                              long shiftPx,
                                              int screenHeight) {
            if (el == null || previousSightings == null || previousSightings.isEmpty()) {
                return false;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return false;
            }
            int centerX = (bounds.getLeft() + bounds.getRight()) / 2;
            int centerY = (bounds.getTop() + bounds.getBottom()) / 2;
            int width = bounds.getRight() - bounds.getLeft();
            int height = bounds.getBottom() - bounds.getTop();
            String className = nullToEmpty(el.getClassName());
            String resource = nullToEmpty(el.getResourceName());
            String text = nullToEmpty(el.getText());
            String desc = nullToEmpty(el.getContentDescription());
            int xSlack = Math.max(48, width / 4);
            int bestShiftMiss = Integer.MAX_VALUE;

            for (ElementSighting prior : previousSightings) {
                if (!check.equals(prior.check) || type != prior.type) {
                    continue;
                }
                if (!className.equals(prior.className) || !sameWidgetLabels(resource, text, desc, prior)) {
                    continue;
                }
                if (Math.abs(centerX - prior.centerX) > xSlack
                        || !similarSpan(width, prior.width)) {
                    continue;
                }
                boolean clipped = touchesVerticalEdge(prior.centerY, prior.height, screenHeight)
                        || touchesVerticalEdge(centerY, height, screenHeight);
                if (!clipped && !similarSpan(height, prior.height)) {
                    continue;
                }
                int dy = prior.centerY - centerY;
                int shiftMiss = (int) Math.abs(dy - shiftPx);
                if (shiftMiss < bestShiftMiss) {
                    bestShiftMiss = shiftMiss;
                }
            }
            return shiftPx > MIN_MEANINGFUL_SHIFT_PX && bestShiftMiss <= SCROLL_MATCH_SLACK_PX;
        }

        /**
         * A header that starts below other content, then pins to the top after
         * the first scroll. Same labels and column, but it moved up by less
         * than the page, and its center is now in the top band. Repeated rows
         * travel with the scroll, so they are not counted. Exactly one prior
         * must resist the scroll — two chrome widgets with the same label stay
         * distinct. Height is not compared: a collapsing bar gets shorter as
         * it docks.
         */
        private boolean isPinnedStickyDuplicate(String check,
                                                AccessibilityCheckResultType type,
                                                ViewHierarchyElement el,
                                                List<ElementSighting> previousSightings,
                                                long shiftPx,
                                                int screenHeight) {
            if (el == null || previousSightings == null || previousSightings.isEmpty()) {
                return false;
            }
            if (shiftPx <= MIN_MEANINGFUL_SHIFT_PX || screenHeight <= 0) {
                return false;
            }
            var bounds = el.getBoundsInScreen();
            if (bounds == null) {
                return false;
            }
            int centerX = (bounds.getLeft() + bounds.getRight()) / 2;
            int centerY = (bounds.getTop() + bounds.getBottom()) / 2;
            int pinBand = (int) (screenHeight * NEW_BAND_TOP_FRACTION);
            if (centerY > pinBand) {
                return false;
            }
            int width = bounds.getRight() - bounds.getLeft();
            String className = nullToEmpty(el.getClassName());
            String resource = nullToEmpty(el.getResourceName());
            String text = nullToEmpty(el.getText());
            String desc = nullToEmpty(el.getContentDescription());
            int xSlack = Math.max(48, width / 4);
            int resisted = 0;
            for (ElementSighting prior : previousSightings) {
                if (!check.equals(prior.check) || type != prior.type) {
                    continue;
                }
                if (!className.equals(prior.className) || !sameWidgetLabels(resource, text, desc, prior)) {
                    continue;
                }
                if (Math.abs(centerX - prior.centerX) > xSlack || !similarSpan(width, prior.width)) {
                    continue;
                }
                int dy = prior.centerY - centerY;
                if (dy >= -SCROLL_MATCH_SLACK_PX && dy < shiftPx - SCROLL_MATCH_SLACK_PX) {
                    resisted++;
                    if (resisted > 1) {
                        return false;
                    }
                }
            }
            return resisted == 1;
        }

        private boolean sameWidgetLabels(String resource, String text, String desc, ElementSighting prior) {
            resource = normalizeIdentityText(resource);
            text = normalizeIdentityText(text);
            desc = normalizeIdentityText(desc);
            String priorResource = normalizeIdentityText(prior.resourceName);
            String priorText = normalizeIdentityText(prior.text);
            String priorDesc = normalizeIdentityText(prior.contentDescription);
            if (resource.equals(priorResource) && text.equals(priorText) && desc.equals(priorDesc)) {
                return true;
            }
            return !resource.isEmpty()
                    && resource.equals(priorResource)
                    && labelsCompatible(text, priorText)
                    && labelsCompatible(desc, priorDesc);
        }

        /** A row cut by the top or bottom fold changes height on the next viewport. */
        private static boolean touchesVerticalEdge(int centerY, int height, int screenHeight) {
            if (screenHeight <= 0 || height <= 0) {
                return false;
            }
            int top = centerY - height / 2;
            int bottom = centerY + height / 2;
            return top <= 8 || bottom >= screenHeight - 8;
        }

        private static boolean labelsCompatible(String a, String b) {
            if (a.equals(b) || a.isEmpty() || b.isEmpty()) {
                return true;
            }
            String left = a.toLowerCase(Locale.US);
            String right = b.toLowerCase(Locale.US);
            return left.startsWith(right) || right.startsWith(left);
        }

        private static boolean similarSpan(int a, int b) {
            int longer = Math.max(a, b);
            int shorter = Math.min(Math.max(a, 1), Math.max(b, 1));
            return shorter * 2 >= longer;
        }

        private boolean sameStableIdentity(ViewHierarchyElement a, ViewHierarchyElement b) {
            if (a == null || b == null) {
                return false;
            }
            return baseIdentity(
                    nullToEmpty(a.getResourceName()),
                    nullToEmpty(a.getText()),
                    nullToEmpty(a.getContentDescription()),
                    nullToEmpty(a.getClassName()))
                    .equals(baseIdentity(
                            nullToEmpty(b.getResourceName()),
                            nullToEmpty(b.getText()),
                            nullToEmpty(b.getContentDescription()),
                            nullToEmpty(b.getClassName())));
        }

        /**
         * Estimates how far content moved vertically between two viewports.
         * Unique labels are trusted when they agree. Repeated rows still vote:
         * the densest cluster of vertical deltas is the scroll, so a short nudge
         * is not replaced by a full-page guess. The nominal swipe distance is
         * only a tie-break between equal clusters, and the fallback when nothing
         * on screen can be tracked.
         */
        private ShiftEstimate measureVerticalShift(Map<String, List<Rect>> before,
                                                   Map<String, List<Rect>> after,
                                                   int screenWidth,
                                                   int screenHeight) {
            List<Long> uniqueDeltas = new ArrayList<>();
            List<Long> pairDeltas = new ArrayList<>();
            int xSlack = Math.max(64, screenWidth / 10);
            for (Map.Entry<String, List<Rect>> entry : before.entrySet()) {
                List<Rect> beforeRects = entry.getValue();
                List<Rect> afterRects = after.get(entry.getKey());
                if (afterRects == null || afterRects.isEmpty() || beforeRects == null || beforeRects.isEmpty()) {
                    continue;
                }
                boolean unique = beforeRects.size() == 1 && afterRects.size() == 1;
                for (Rect from : beforeRects) {
                    for (Rect to : afterRects) {
                        if (!similarColumn(from, to, xSlack)) {
                            continue;
                        }
                        long delta = from.centerY() - to.centerY();
                        if (delta <= MIN_MEANINGFUL_SHIFT_PX || delta >= screenHeight) {
                            continue;
                        }
                        pairDeltas.add(delta);
                        if (unique) {
                            uniqueDeltas.add(delta);
                        }
                    }
                }
            }
            long nominal = Math.round(screenHeight * DEFAULT_SHIFT_FRACTION);
            Long uniqueConsensus = consensusDelta(uniqueDeltas, screenHeight);
            if (uniqueConsensus != null) {
                Log.i(TAG, "Scroll shift from " + uniqueDeltas.size()
                        + " unique element(s): " + uniqueConsensus + "px");
                return new ShiftEstimate(uniqueConsensus, true);
            }
            Long paired = consensusNear(pairDeltas, nominal, screenHeight);
            if (paired != null) {
                Log.i(TAG, "Scroll shift from " + pairDeltas.size()
                        + " element pair(s): " + paired + "px (nominal " + nominal + "px)");
                return new ShiftEstimate(paired, true);
            }
            Log.i(TAG, "No scroll match — using nominal shift " + nominal + "px");
            return new ShiftEstimate(nominal, false);
        }

        /**
         * Prefer a screenshot alignment when it agrees with element tracking, or
         * when element tracking found nothing and fell back to the finger travel.
         * A repeating list can align one row off; element tracking wins that tie.
         */
        private long chooseScrollShift(ShiftEstimate tracked, Long visualShift, int screenHeight, int viewport) {
            if (visualShift != null && tracked.fromElements
                    && Math.abs(visualShift - tracked.px) <= screenHeight / 6L) {
                Log.i(TAG, "Viewport " + viewport + " scroll shift from screenshot: " + visualShift
                        + "px (elements " + tracked.px + "px)");
                return visualShift;
            }
            if (visualShift != null && !tracked.fromElements) {
                Log.i(TAG, "Viewport " + viewport + " scroll shift from screenshot: " + visualShift
                        + "px (element tracking had no match, nominal was " + tracked.px + "px)");
                return visualShift;
            }
            Log.i(TAG, "Viewport " + viewport + " scroll shift: " + tracked.px + "px");
            return tracked.px;
        }

        /** Downsampled grayscale of the page body, used only to measure scroll. */
        private static final class VisualColumn {
            final int step;
            final int width;
            final int[] luma;

            VisualColumn(int step, int width, int[] luma) {
                this.step = step;
                this.width = width;
                this.luma = luma;
            }

            int height() {
                return width == 0 ? 0 : luma.length / width;
            }
        }

        private static final class ShiftEstimate {
            final long px;
            final boolean fromElements;

            ShiftEstimate(long px, boolean fromElements) {
                this.px = px;
                this.fromElements = fromElements;
            }
        }

        private VisualColumn sampleVisual(Bitmap screenshot, DisplayMetrics metrics) {
            if (screenshot == null || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
                return null;
            }
            Bitmap source = screenshot;
            Bitmap copied = null;
            if (screenshot.getConfig() == Bitmap.Config.HARDWARE) {
                copied = screenshot.copy(Bitmap.Config.ARGB_8888, false);
                if (copied == null) {
                    return null;
                }
                source = copied;
            }
            try {
                float scale = screenshotScale(source, metrics);
                int step = 8;
                int left = metrics.widthPixels / 6;
                int right = metrics.widthPixels - left;
                int top = metrics.heightPixels / 10;
                int bottom = metrics.heightPixels - metrics.heightPixels / 12;
                int width = Math.max(1, (right - left) / step);
                int height = Math.max(1, (bottom - top) / step);
                int[] luma = new int[width * height];
                int bitmapWidth = source.getWidth();
                int bitmapHeight = source.getHeight();
                for (int y = 0; y < height; y++) {
                    int sampleY = Math.min(bitmapHeight - 1,
                            Math.max(0, Math.round((top + y * step) * scale)));
                    int row = y * width;
                    for (int x = 0; x < width; x++) {
                        int sampleX = Math.min(bitmapWidth - 1,
                                Math.max(0, Math.round((left + x * step) * scale)));
                        int color = source.getPixel(sampleX, sampleY);
                        luma[row + x] = (Color.red(color) * 3 + Color.green(color) * 6 + Color.blue(color)) / 10;
                    }
                }
                return new VisualColumn(step, width, luma);
            } finally {
                if (copied != null && !copied.isRecycled()) {
                    copied.recycle();
                }
            }
        }

        /**
         * Display pixels the page moved up. Null when the frames do not show one
         * clear alignment (a video, a static page, or a pattern that matches
         * equally well at several offsets).
         */
        private Long measureVisualShift(VisualColumn before, VisualColumn after, int screenHeight) {
            if (before == null || after == null
                    || before.width != after.width
                    || before.step != after.step
                    || before.width <= 0) {
                return null;
            }
            int sharedRows = Math.min(before.height(), after.height());
            if (sharedRows < 16) {
                return null;
            }
            int maxShift = (int) (screenHeight * 0.92f);
            int capacity = maxShift / before.step + 1;
            long[] means = new long[capacity];
            int[] offsets = new int[capacity];
            int countShifts = 0;
            for (int dy = 0; dy <= maxShift && countShifts < capacity; dy += before.step) {
                int rowShift = dy / before.step;
                int overlap = sharedRows - rowShift;
                if (overlap < 12) {
                    break;
                }
                long sad = 0;
                int count = 0;
                for (int y = 0; y < overlap; y++) {
                    int from = (y + rowShift) * before.width;
                    int to = y * after.width;
                    for (int x = 0; x < before.width; x++) {
                        sad += Math.abs(before.luma[from + x] - after.luma[to + x]);
                        count++;
                    }
                }
                means[countShifts] = sad / Math.max(1, count);
                offsets[countShifts] = dy;
                countShifts++;
            }
            if (countShifts == 0) {
                return null;
            }
            int bestIndex = 0;
            for (int i = 1; i < countShifts; i++) {
                if (means[i] < means[bestIndex]) {
                    bestIndex = i;
                }
            }
            int bestDy = offsets[bestIndex];
            long best = means[bestIndex];
            long zero = means[0];
            long rival = Long.MAX_VALUE;
            for (int i = 0; i < countShifts; i++) {
                if (Math.abs(offsets[i] - bestDy) < 48) {
                    continue;
                }
                if (means[i] < rival) {
                    rival = means[i];
                }
            }
            if (bestDy <= MIN_MEANINGFUL_SHIFT_PX) {
                return null;
            }
            if (zero - best < 8 || rival - best < 4) {
                return null;
            }
            return (long) bestDy;
        }

        /** Median of the tightest group, when at least two samples agree. */
        private static Long consensusDelta(List<Long> deltas, int screenHeight) {
            if (deltas.size() < 2) {
                return null;
            }
            Collections.sort(deltas);
            int band = Math.max(48, screenHeight / 30);
            int[] window = densestWindow(deltas, 0, deltas.size(), band);
            int count = window[1] - window[0];
            if (count < 2) {
                return null;
            }
            return deltas.get(window[0] + (count - 1) / 2);
        }

        /**
         * Median of the densest delta cluster. A clearly larger cluster wins even
         * when the page moved less than the finger. Equal clusters (a row of
         * identical widgets, matched to the neighbor as often as to itself) stay
         * with the one nearest the swipe distance.
         */
        private static Long consensusNear(List<Long> deltas, long seed, int screenHeight) {
            if (deltas.size() < 2) {
                return null;
            }
            Collections.sort(deltas);
            int band = Math.max(64, screenHeight / 20);
            int[] primary = densestWindow(deltas, 0, deltas.size(), band);
            int primaryCount = primary[1] - primary[0];
            if (primaryCount < 2) {
                return null;
            }
            long primaryMedian = deltas.get(primary[0] + (primaryCount - 1) / 2);
            int[] left = densestWindow(deltas, 0, primary[0], band);
            int[] right = densestWindow(deltas, primary[1], deltas.size(), band);
            int leftCount = left[1] - left[0];
            int rightCount = right[1] - right[0];
            int rivalCount = Math.max(leftCount, rightCount);
            if (primaryCount > rivalCount || rivalCount < 2) {
                return primaryMedian;
            }
            long rivalMedian = leftCount >= rightCount
                    ? deltas.get(left[0] + (leftCount - 1) / 2)
                    : deltas.get(right[0] + (rightCount - 1) / 2);
            long nearer = Math.abs(primaryMedian - seed) <= Math.abs(rivalMedian - seed)
                    ? primaryMedian
                    : rivalMedian;
            // A cluster near the finger travel is the swipe we asked for.
            if (Math.abs(nearer - seed) <= screenHeight / 5L) {
                return nearer;
            }
            // The page moved less than the finger. Identical rows then pair
            // both with themselves and with the next row; the smaller delta
            // is the widget matched to itself.
            return Math.min(primaryMedian, rivalMedian);
        }

        /** @return {@code [startInclusive, endExclusive]} of the densest band in {@code [from, to)}. */
        private static int[] densestWindow(List<Long> sorted, int from, int to, int band) {
            int bestStart = from;
            int bestEnd = from;
            int right = from;
            for (int left = from; left < to; left++) {
                if (right < left) {
                    right = left;
                }
                while (right < to && sorted.get(right) - sorted.get(left) <= band) {
                    right++;
                }
                if (right - left > bestEnd - bestStart) {
                    bestStart = left;
                    bestEnd = right;
                }
            }
            return new int[]{bestStart, bestEnd};
        }

        private static boolean similarColumn(Rect from, Rect to, int xSlack) {
            if (Math.abs(from.centerX() - to.centerX()) > xSlack) {
                return false;
            }
            return similarSpan(from.height(), to.height());
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
