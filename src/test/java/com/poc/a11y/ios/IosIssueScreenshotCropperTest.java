package com.poc.a11y.ios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IosIssueScreenshotCropperTest {

    @TempDir
    Path shotsDir;

    @Test
    void cropsEachElementAndReusesFileForSameWidget() throws Exception {
        byte[] png = solidPng(390, 844, Color.WHITE);
        IosIssueScreenshotCropper cropper = new IosIssueScreenshotCropper();

        Map<String, Object> first = issue("Button", "add", 120, 200, 48, 48);
        Map<String, Object> sameWidget = issue("Button", "add", 122, 201, 48, 48);
        Map<String, Object> other = issue("StaticText", "price", 80, 400, 200, 24);

        int next = cropper.attachCrops(png, 390, 844, List.of(first, sameWidget, other), shotsDir, 1);

        assertEquals(3, next);
        String firstShot = (String) first.get("screenshotFile");
        String reused = (String) sameWidget.get("screenshotFile");
        String otherShot = (String) other.get("screenshotFile");
        assertNotNull(firstShot);
        assertEquals(firstShot, reused);
        assertNotNull(otherShot);
        assertTrue(!firstShot.equals(otherShot));
        assertTrue(Files.isRegularFile(Path.of(firstShot)));
        assertTrue(Files.isRegularFile(Path.of(otherShot)));

        BufferedImage crop = ImageIO.read(Path.of(firstShot).toFile());
        assertTrue(crop.getWidth() < 390);
        assertTrue(crop.getHeight() < 844);
        assertTrue(crop.getWidth() >= 240);
        assertTrue(crop.getHeight() >= 160);
    }

    @Test
    void parsesNsFrameStringWhenRectMapMissing() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("type", "Button");
        attrs.put("frame", "{{129, 65}, {135, 18}}");
        Map<String, Object> issue = new HashMap<>();
        issue.put("elementAttributes", attrs);

        IosIssueScreenshotCropper.ElementRect rect = IosIssueScreenshotCropper.resolveRect(issue);
        assertNotNull(rect);
        assertEquals(129, rect.x());
        assertEquals(65, rect.y());
        assertEquals(135, rect.width());
        assertEquals(18, rect.height());
    }

    private static Map<String, Object> issue(String type, String name, int x, int y, int w, int h) {
        Map<String, Object> rect = new HashMap<>();
        rect.put("x", x);
        rect.put("y", y);
        rect.put("width", w);
        rect.put("height", h);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("type", type);
        attrs.put("name", name);
        attrs.put("label", name);
        attrs.put("rect", rect);
        Map<String, Object> issue = new HashMap<>();
        issue.put("auditType", "XCUIAccessibilityAuditTypeHitRegion");
        issue.put("elementAttributes", attrs);
        return issue;
    }

    private static byte[] solidPng(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
