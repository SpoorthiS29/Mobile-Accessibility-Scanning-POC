package com.poc.a11y.atf.harness;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.android.apps.common.testing.accessibility.framework.ocr.OcrEngine;
import com.google.android.apps.common.testing.accessibility.framework.ocr.OcrResult;
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage;
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.Image;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.apps.common.testing.accessibility.framework.ocr.TextComponent;
import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect;
import com.google.common.collect.ImmutableList;

public class MlKitOcrEngine implements OcrEngine {

    private static final String TAG = "MlKitOcrEngine";

    private TextRecognizer recognizer;
    private final Context context;

    public MlKitOcrEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public OcrResult detect(Image screenshot) {

        if (screenshot == null) {
            Log.w(TAG, "OCR screenshot is null");
            return null;
        }

        if (!(screenshot instanceof BitmapImage)) {
            Log.w(TAG, "Unsupported Image type: " + screenshot.getClass().getName());
            return null;
        }

        Bitmap bitmap = ((BitmapImage) screenshot).getBitmap();

        if (bitmap == null) {
            Log.w(TAG, "Bitmap is null");
            return null;
        }

        Log.i(TAG, "Starting ML Kit OCR: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        try {

            if (recognizer == null) {

                Log.i(TAG, "Creating recognizer...");
                recognizer = TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS);

                Log.i(TAG, "Recognizer created");
            }
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

            Text result = com.google.android.gms.tasks.Tasks.await(recognizer.process(inputImage));

            Log.i(TAG, "ML Kit OCR completed");

            ImmutableList.Builder<TextComponent> textComponents =
                    ImmutableList.builder();

            for (Text.TextBlock block : result.getTextBlocks()) {

                Log.i(TAG, "OCR BLOCK: " + block.getText());

                TextComponent component = createTextComponent(block);

                if (component != null) {
                    textComponents.add(component);
                }

                for (Text.Line line : block.getLines()) {
                    Log.i(TAG, "OCR LINE: " + line.getText());
                }
            }

            ImmutableList<TextComponent> components = textComponents.build();

            Log.i(TAG, "OCR COMPONENT COUNT: " + components.size());

            OcrResult ocrResult = new OcrResult(components);

            Log.i(TAG, "OCR RESULT CREATED: " + (ocrResult != null));

            return ocrResult;

        } catch (Exception e) {
            Log.e(TAG, "ML Kit OCR failed", e);
            return null;
        }
    }

    private TextComponent createTextComponent(Text.TextBlock block) {
        Rect bounds = toAtfRect(block.getBoundingBox());

        if (bounds == null) {
            return null;
        }

        TextComponent.Builder builder =
                TextComponent.newBuilder(block.getText(), bounds);

        return builder.build();
    }

    private Rect toAtfRect(android.graphics.Rect androidRect) {
        if (androidRect == null) {
            return null;
        }

        return new Rect(
                androidRect.left,
                androidRect.top,
                androidRect.right,
                androidRect.bottom);
    }

}