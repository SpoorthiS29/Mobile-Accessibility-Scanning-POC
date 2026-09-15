package com.poc.a11y.atf;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
public class AxmlDump {
  public static void main(String[] args) throws Exception {
    Path apk = Path.of(args[0]);
    try (ZipFile zip = new ZipFile(apk.toFile())) {
      byte[] data = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readAllBytes();
      ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
      buf.getShort(); buf.getShort(); int fileSize = Math.min(buf.getInt(), data.length);
      String[] pool = null;
      while (buf.position() + 8 <= fileSize) {
        int start = buf.position();
        int type = Short.toUnsignedInt(buf.getShort());
        int headerSize = Short.toUnsignedInt(buf.getShort());
        int size = buf.getInt();
        if (type == 0x0001) {
          pool = invokePool(data, start, size);
          System.out.println("pool size=" + pool.length);
          for (int i = 0; i < Math.min(pool.length, 40); i++) System.out.println("  [" + i + "] " + pool[i]);
        } else if (type == 0x0102 && pool != null) {
          dumpElement(data, start, headerSize, pool);
        }
        buf.position(start + size);
      }
    }
  }
  static String[] invokePool(byte[] data, int start, int size) throws Exception {
    var m = ApkManifestReader.class.getDeclaredMethod("readStringPool", byte[].class, int.class, int.class);
    m.setAccessible(true);
    return (String[]) m.invoke(null, data, start, size);
  }
  static void dumpElement(byte[] data, int chunkStart, int headerSize, String[] pool) {
    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    buf.position(chunkStart + 16);
    buf.getInt();
    int nameIdx = buf.getInt();
    int attributeStart = Short.toUnsignedInt(buf.getShort());
    int attributeSize = Short.toUnsignedInt(buf.getShort());
    int attributeCount = Short.toUnsignedInt(buf.getShort());
    String name = (nameIdx >= 0 && nameIdx < pool.length) ? pool[nameIdx] : ("idx="+nameIdx);
    System.out.println("ELEMENT " + name + " attrs=" + attributeCount + " attrStart=" + attributeStart + " attrSize=" + attributeSize);
    int attrsBase = chunkStart + 16 + attributeStart;
    for (int i = 0; i < attributeCount; i++) {
      int pos = attrsBase + i * (attributeSize < 20 ? 20 : attributeSize);
      buf.position(pos);
      int ns = buf.getInt();
      int attrNameIdx = buf.getInt();
      int raw = buf.getInt();
      buf.getShort(); buf.get();
      int dataType = buf.get() & 0xff;
      int typed = buf.getInt();
      String attrName = (attrNameIdx >= 0 && attrNameIdx < pool.length) ? pool[attrNameIdx] : ("idx="+attrNameIdx);
      System.out.println("  attr=" + attrName + " type=0x" + Integer.toHexString(dataType) + " data=" + typed + " raw=" + raw);
    }
  }
}
