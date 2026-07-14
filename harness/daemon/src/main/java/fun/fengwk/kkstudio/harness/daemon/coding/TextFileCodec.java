package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Minimal text encoding/BOM preservation used by write and edit. */
final class TextFileCodec {

  private TextFileCodec() {}

  static Decoded decode(byte[] bytes) {
    if (starts(bytes, (byte) 0xef, (byte) 0xbb, (byte) 0xbf)) {
      return new Decoded(
          new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
          StandardCharsets.UTF_8,
          3);
    }
    if (starts(bytes, (byte) 0xff, (byte) 0xfe)) {
      return new Decoded(
          new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE),
          StandardCharsets.UTF_16LE,
          2);
    }
    if (starts(bytes, (byte) 0xfe, (byte) 0xff)) {
      return new Decoded(
          new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE),
          StandardCharsets.UTF_16BE,
          2);
    }
    if (OutputLimiter.isBinary(bytes)) {
      throw new IllegalArgumentException("file appears to be binary");
    }
    return new Decoded(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8, 0);
  }

  static byte[] encode(String text, Charset charset, int bomLength) {
    byte[] content = text.getBytes(charset);
    if (bomLength == 0) {
      return content;
    }
    byte[] bom =
        charset.equals(StandardCharsets.UTF_8)
            ? new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
            : charset.equals(StandardCharsets.UTF_16LE)
                ? new byte[] {(byte) 0xff, (byte) 0xfe}
                : new byte[] {(byte) 0xfe, (byte) 0xff};
    byte[] result = Arrays.copyOf(bom, bom.length + content.length);
    System.arraycopy(content, 0, result, bom.length, content.length);
    return result;
  }

  private static boolean starts(byte[] bytes, byte... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (bytes[index] != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  record Decoded(String text, Charset charset, int bomLength) {}
}
