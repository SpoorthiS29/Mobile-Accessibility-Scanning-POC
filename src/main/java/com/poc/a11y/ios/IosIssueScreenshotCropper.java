package com.poc.a11y.ios;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crops the failing widget from a full iOS screenshot, matching Android ATF:
 * surrounding context, a red highlight, and one PNG reused when several
 * checks fire on the same element. Issues without a usable rect get no image.
 */
@Component
public class IosIssueScreenshotCropper {

    private static final Logger log = LoggerFactory.getLogger(IosIssueScreenshotCropper.class);

    static final String SCREENSHOT_FILE_KEY = "screenshotFile";

    private static final int CROP_PADDING_PX = 48;
    private static final int MIN_CROP_WIDTH_PX = 240;
    private static final int MIN_CROP_HEIGHT_PX = 160;
    private static final int POSITION_BUCKET_PX = 32;

    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "\\{\\{\\s*(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*}\\s*,\\s*\\{\\s*(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*}}");

    public int attachCrops(byte[] screenshotPng,
                           int windowWidth,
                           int windowHeight,
                           List<Map<String, Object>> issues,
                           Path shotsDir,
                           int nextShotIndex) {
        if (screenshotPng == null || screenshotPng.length == 0 || issues == null || issues.isEmpty()) {
            return nextShotIndex;
        }
        BufferedImage full;
        try {
            full = ImageIO.read(new ByteArrayInputStream(screenshotPng));
        } catch (IOException e) {
            log.warn("Could not decode iOS screenshot for cropping: {}", e.getMessage());
            return nextShotIndex;
        }
        if (full == null) {
            log.warn("iOS screenshot decoded to null — skipping crops");
            return nextShotIndex;
        }

        float scaleX = scale(full.getWidth(), windowWidth);
        float scaleY = scale(full.getHeight(), windowHeight);
        log.info("iOS screenshot {}x{} window={}x{} cropScaleX={} cropScaleY={}",
                full.getWidth(), full.getHeight(), windowWidth, windowHeight, scaleX, scaleY);

        Map<String, String> cropByElement = new HashMap<>();
        int index = nextShotIndex;
        for (Map<String, Object> issue : issues) {
            ElementRect rect = resolveRect(issue);
            if (rect == null || rect.isEmpty()) {
                log.warn("Skipping crop — no usable element rect");
                continue;
            }
            String identity = elementIdentity(issue, rect);
            String existing = cropByElement.get(identity);
            if (existing != null) {
                issue.put(SCREENSHOT_FILE_KEY, existing);
                continue;
            }
            Path file = cropElement(full, rect, scaleX, scaleY, shotsDir, index);
            if (file != null) {
                String absolute = file.toAbsolutePath().toString();
                issue.put(SCREENSHOT_FILE_KEY, absolute);
                cropByElement.put(identity, absolute);
                index++;
            }
        }
        return index;
    }

    private Path cropElement(BufferedImage full,
                             ElementRect bounds,
                             float scaleX,
                             float scaleY,
                             Path shotsDir,
                             int index) {
        int elLeft = Math.round(bounds.x * scaleX);
        int elTop = Math.round(bounds.y * scaleY);
        int elRight = Math.round((bounds.x + bounds.width) * scaleX);
        int elBottom = Math.round((bounds.y + bounds.height) * scaleY);
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

        BufferedImage marked = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = marked.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(full, 0, 0, width, height, left, top, right, bottom, null);
            g.setStroke(new BasicStroke(6f));
            g.setColor(Color.RED);
            int x1 = clamp(elLeft - left, 0, width - 1);
            int y1 = clamp(elTop - top, 0, height - 1);
            int x2 = clamp(elRight - left, x1 + 1, width);
            int y2 = clamp(elBottom - top, y1 + 1, height);
            g.drawRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
        } finally {
            g.dispose();
        }

        Path out = shotsDir.resolve(String.format(Locale.US, "issue-%04d.png", index));
        try {
            if (!ImageIO.write(marked, "png", out.toFile())) {
                log.warn("Failed to encode crop {}", out.getFileName());
                return null;
            }
        } catch (IOException e) {
            log.warn("Failed to write crop {}: {}", out, e.getMessage());
            return null;
        }
        return out;
    }

    static ElementRect resolveRect(Map<String, Object> issue) {
        Map<String, Object> attrs = attributes(issue);
        if (attrs != null) {
            ElementRect fromRect = parseRectMap(attrs.get("rect"));
            if (fromRect != null) {
                return fromRect;
            }
            ElementRect fromFrame = parseFrame(str(attrs.get("frame")));
            if (fromFrame != null) {
                return fromFrame;
            }
        }
        return parseFrame(str(issue.get("elementDescription")));
    }

    private static ElementRect parseRectMap(Object rectObj) {
        if (!(rectObj instanceof Map<?, ?> map)) {
            return null;
        }
        Integer x = toInt(map.get("x"));
        Integer y = toInt(map.get("y"));
        Integer width = toInt(map.get("width"));
        Integer height = toInt(map.get("height"));
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        return new ElementRect(x, y, width, height);
    }

    private static ElementRect parseFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        Matcher matcher = FRAME_PATTERN.matcher(frame);
        if (!matcher.find()) {
            return null;
        }
        try {
            int x = Math.round(Float.parseFloat(matcher.group(1)));
            int y = Math.round(Float.parseFloat(matcher.group(2)));
            int width = Math.round(Float.parseFloat(matcher.group(3)));
            int height = Math.round(Float.parseFloat(matcher.group(4)));
            return new ElementRect(x, y, width, height);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String elementIdentity(Map<String, Object> issue, ElementRect rect) {
        Map<String, Object> attrs = attributes(issue);
        String type = attrs == null ? "" : nullToEmpty(str(attrs.get("type")));
        String name = attrs == null ? "" : nullToEmpty(str(attrs.get("name")));
        String label = attrs == null ? "" : nullToEmpty(str(attrs.get("label")));
        int qx = ((rect.x + rect.width / 2) / POSITION_BUCKET_PX) * POSITION_BUCKET_PX;
        int qy = ((rect.y + rect.height / 2) / POSITION_BUCKET_PX) * POSITION_BUCKET_PX;
        return type + "|" + name + "|" + label + "|@" + qx + "," + qy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> issue) {
        Object attrs = issue.get("elementAttributes");
        if (attrs instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static float scale(int imagePx, int windowPx) {
        if (windowPx <= 0) {
            return 1f;
        }
        return (float) imagePx / windowPx;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number number) {
            return Math.round(number.floatValue());
        }
        String text = str(value);
        if (text == null) {
            return null;
        }
        try {
            return Math.round(Float.parseFloat(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record ElementRect(int x, int y, int width, int height) {
        boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }
}
