package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Preserves incomplete UTF-8 suffix bytes between streamed process output chunks. */
final class Utf8StreamDecoder {

  private byte[] pending = new byte[0];

  String decode(byte[] bytes, int length) {
    if (length < 0 || length > bytes.length) {
      throw new IllegalArgumentException("length is outside the source buffer");
    }
    byte[] combined = new byte[pending.length + length];
    System.arraycopy(pending, 0, combined, 0, pending.length);
    System.arraycopy(bytes, 0, combined, pending.length, length);
    int suffixLength = incompleteSuffixLength(combined);
    int decodedLength = combined.length - suffixLength;
    pending = Arrays.copyOfRange(combined, decodedLength, combined.length);
    return new String(combined, 0, decodedLength, StandardCharsets.UTF_8);
  }

  String finish() {
    String result = new String(pending, StandardCharsets.UTF_8);
    pending = new byte[0];
    return result;
  }

  private static int incompleteSuffixLength(byte[] bytes) {
    if (bytes.length == 0) {
      return 0;
    }
    int index = bytes.length - 1;
    int continuationCount = 0;
    while (index >= 0 && isContinuation(bytes[index])) {
      continuationCount++;
      index--;
    }
    if (index < 0) {
      return Math.min(bytes.length, 3);
    }
    int expectedLength = sequenceLength(bytes[index]);
    if (expectedLength <= 1) {
      return 0;
    }
    int availableLength = continuationCount + 1;
    return availableLength < expectedLength ? availableLength : 0;
  }

  private static boolean isContinuation(byte value) {
    return (value & 0xc0) == 0x80;
  }

  private static int sequenceLength(byte value) {
    int unsigned = value & 0xff;
    if ((unsigned & 0x80) == 0) {
      return 1;
    }
    if ((unsigned & 0xe0) == 0xc0) {
      return 2;
    }
    if ((unsigned & 0xf0) == 0xe0) {
      return 3;
    }
    if ((unsigned & 0xf8) == 0xf0) {
      return 4;
    }
    return 1;
  }
}
