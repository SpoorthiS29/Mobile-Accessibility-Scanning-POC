package com.poc.a11y.atf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@code package}, {@code versionCode}, and {@code versionName} from an APK's
 * binary {@code AndroidManifest.xml} without depending on aapt.
 */
final class ApkManifestReader {

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int UTF8_FLAG = 1 << 8;

    private ApkManifestReader() {
    }

    static PackageIdentity read(Path apk) {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) {
                throw new IllegalStateException("APK has no AndroidManifest.xml: " + apk);
            }
            byte[] data = zip.getInputStream(entry).readAllBytes();
            return parse(data);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read APK manifest: " + apk, e);
        }
    }

    static PackageIdentity parse(byte[] axml) {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        buf.getShort();
        buf.getShort();
        int fileSize = Math.min(buf.getInt(), axml.length);

        String[] pool = null;
        String packageName = null;
        String versionCode = null;
        String versionName = null;

        while (buf.position() + 8 <= fileSize) {
            int chunkStart = buf.position();
            int type = Short.toUnsignedInt(buf.getShort());
            int headerSize = Short.toUnsignedInt(buf.getShort());
            int chunkSize = buf.getInt();
            if (chunkSize < 8 || chunkStart + chunkSize > axml.length) {
                break;
            }
            if (type == RES_STRING_POOL_TYPE) {
                pool = readStringPool(axml, chunkStart, chunkSize);
            } else if (type == RES_XML_START_ELEMENT_TYPE && pool != null) {
                ManifestAttrs attrs = readStartElement(axml, chunkStart, headerSize, pool);
                if (attrs != null && "manifest".equals(attrs.name)) {
                    packageName = attrs.packageName;
                    versionCode = attrs.versionCode;
                    versionName = attrs.versionName;
                    break;
                }
            }
            buf.position(chunkStart + chunkSize);
        }

        if (packageName == null && versionCode == null) {
            throw new IllegalStateException("Could not read package/version from AndroidManifest.xml");
        }
        return new PackageIdentity(packageName, versionCode, versionName, null);
    }

    private static String[] readStringPool(byte[] data, int chunkStart, int chunkSize) {
        ByteBuffer buf = ByteBuffer.wrap(data, chunkStart, chunkSize).slice().order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8);
        int stringCount = buf.getInt();
        buf.getInt();
        int flags = buf.getInt();
        int stringsStart = buf.getInt();
        buf.getInt();
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = buf.getInt();
        }
        String[] strings = new String[stringCount];
        int stringsBase = chunkStart + stringsStart;
        for (int i = 0; i < stringCount; i++) {
            int pos = stringsBase + offsets[i];
            if (pos < 0 || pos >= data.length) {
                strings[i] = "";
                continue;
            }
            strings[i] = utf8 ? readUtf8(data, pos) : readUtf16(data, pos);
        }
        return strings;
    }

    private static String readUtf8(byte[] data, int pos) {
        int charLen = data[pos++] & 0xff;
        if ((charLen & 0x80) != 0) {
            charLen = ((charLen & 0x7f) << 8) | (data[pos++] & 0xff);
        }
        int byteLen = data[pos++] & 0xff;
        if ((byteLen & 0x80) != 0) {
            byteLen = ((byteLen & 0x7f) << 8) | (data[pos++] & 0xff);
        }
        int remaining = data.length - pos;
        int len = Math.min(byteLen, remaining);
        return new String(data, pos, Math.max(0, len), StandardCharsets.UTF_8);
    }

    private static String readUtf16(byte[] data, int pos) {
        int charLen = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8);
        pos += 2;
        if ((charLen & 0x8000) != 0) {
            charLen = ((charLen & 0x7fff) << 16) | ((data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8));
            pos += 2;
        }
        int remainingChars = Math.max(0, (data.length - pos) / 2);
        int len = Math.min(charLen, remainingChars);
        return new String(data, pos, len * 2, StandardCharsets.UTF_16LE);
    }

    private static ManifestAttrs readStartElement(byte[] data, int chunkStart, int headerSize, String[] pool) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int extStart = chunkStart + Math.max(headerSize, 16);
        if (extStart + 20 > data.length) {
            return null;
        }
        buf.position(chunkStart + 16);
        buf.getInt();
        int nameIdx = buf.getInt();
        int attributeStart = Short.toUnsignedInt(buf.getShort());
        int attributeSize = Short.toUnsignedInt(buf.getShort());
        int attributeCount = Short.toUnsignedInt(buf.getShort());
        if (attributeSize < 20) {
            attributeSize = 20;
        }
        int attrsBase = chunkStart + 16 + attributeStart;
        String packageName = null;
        String versionCode = null;
        String versionName = null;
        for (int i = 0; i < attributeCount; i++) {
            int attrPos = attrsBase + i * attributeSize;
            if (attrPos + 20 > data.length) {
                break;
            }
            buf.position(attrPos);
            buf.getInt();
            int attrNameIdx = buf.getInt();
            int rawValue = buf.getInt();
            buf.getShort();
            buf.get();
            int dataType = buf.get() & 0xff;
            int typedData = buf.getInt();
            String attrName = stringAt(pool, attrNameIdx);
            if ("package".equals(attrName)) {
                packageName = typedString(pool, rawValue, dataType, typedData);
            } else if ("versionCode".equals(attrName)) {
                versionCode = typedIntOrString(pool, rawValue, dataType, typedData);
            } else if ("versionName".equals(attrName)) {
                versionName = typedString(pool, rawValue, dataType, typedData);
            }
        }
        return new ManifestAttrs(stringAt(pool, nameIdx), packageName, versionCode, versionName);
    }

    private static String typedString(String[] pool, int rawValue, int dataType, int data) {
        if (dataType == TYPE_STRING) {
            return stringAt(pool, data);
        }
        if (rawValue >= 0) {
            return stringAt(pool, rawValue);
        }
        return null;
    }

    private static String typedIntOrString(String[] pool, int rawValue, int dataType, int data) {
        if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
            return Integer.toUnsignedString(data);
        }
        return typedString(pool, rawValue, dataType, data);
    }

    private static String stringAt(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) {
            return null;
        }
        return pool[index];
    }

    private record ManifestAttrs(String name, String packageName, String versionCode, String versionName) {
    }
}
