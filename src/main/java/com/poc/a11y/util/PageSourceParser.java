package com.poc.a11y.util;

import com.poc.a11y.model.UiElement;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the XML returned by AndroidDriver#getPageSource() into a flat list
 * of UiElement records, each carrying a computed xpath so findings can be
 * traced back to a specific node.
 */
@Component
public class PageSourceParser {

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    public List<UiElement> parse(String xml) {
        List<UiElement> result = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();

            Map<String, Integer> siblingIndex = new HashMap<>();
            walk(doc.getDocumentElement(), "", result, siblingIndex);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Appium page source XML", e);
        }
        return result;
    }

    private void walk(Element node, String parentPath, List<UiElement> out, Map<String, Integer> siblingIndex) {
        String tag = node.getTagName();
        String key = parentPath + "/" + tag;
        int idx = siblingIndex.merge(key, 1, Integer::sum);
        String currentPath = key + "[" + idx + "]";

        UiElement el = new UiElement();
        el.setXpath(currentPath);
        el.setClassName(attr(node, "class", tag));
        el.setResourceId(attr(node, "resource-id", ""));
        el.setContentDesc(attr(node, "content-desc", ""));
        el.setText(attr(node, "text", ""));
        el.setClickable(boolAttr(node, "clickable"));
        el.setFocusable(boolAttr(node, "focusable"));
        el.setCheckable(boolAttr(node, "checkable"));
        el.setEnabled(boolAttrDefaultTrue(node, "enabled"));
        el.setPassword(boolAttr(node, "password"));

        Matcher m = BOUNDS_PATTERN.matcher(attr(node, "bounds", ""));
        if (m.find()) {
            el.setX1(Integer.parseInt(m.group(1)));
            el.setY1(Integer.parseInt(m.group(2)));
            el.setX2(Integer.parseInt(m.group(3)));
            el.setY2(Integer.parseInt(m.group(4)));
        }

        // Only keep actual UI nodes (skip the synthetic <hierarchy> root)
        if (!"hierarchy".equals(tag)) {
            out.add(el);
        }

        NodeList children = node.getChildNodes();
        Map<String, Integer> childSiblingIndex = new HashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                walk((Element) child, currentPath, out, childSiblingIndex);
            }
        }
    }

    private String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private boolean boolAttr(Element el, String name) {
        return "true".equalsIgnoreCase(el.getAttribute(name));
    }

    private boolean boolAttrDefaultTrue(Element el, String name) {
        String v = el.getAttribute(name);
        return v == null || v.isEmpty() || "true".equalsIgnoreCase(v);
    }
}
