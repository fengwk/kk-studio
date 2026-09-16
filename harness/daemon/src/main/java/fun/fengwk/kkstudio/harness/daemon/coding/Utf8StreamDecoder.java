package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** 在流式进程输出分块之间保留不完整的 UTF-8 后缀字节。 */
final class Utf8StreamDecoder {

  private byte[] pending = new byte[0];

  String decode(byte[] bytes, int length) {
    if (length < 0 || length > bytes.length) {
      throw new IllegalArgumentException("length is outside the source buffer");
    }
    byte[] combined = new byte[pending.length + length];
    System.arraycopy(pending, 0, combined, 0, pending.length);
    System.arraycopy(bytes, 0, combined, pending.length, length);
    int suffixLength = incompleteSuffixLength(combined, combined.length);
    int decodedLength = combined.length - suffixLength;
    pending = Arrays.copyOfRange(combined, decodedLength, combined.length);
    return new String(combined, 0, decodedLength, StandardCharsets.UTF_8);
  }

  String finish() {
    String result = new String(pending, StandardCharsets.UTF_8);
    pending = new byte[0];
    return result;
  }

  /**
   * 把长度收缩到不切断 UTF-8 多字节字符的位置。
   *
   * <p>预览在任意字节偏移处截断，而该偏移可能落在字符内部；按末尾不完整序列的长度回退即可得到完整字符边界。
   */
  static int alignToCharacterBoundary(byte[] bytes, int limit) {
    Objects.requireNonNull(bytes, "bytes");
    if (limit < 0 || limit > bytes.length) {
      throw new IllegalArgumentException("limit is outside the source buffer");
    }
    return limit - incompleteSuffixLength(bytes, limit);
  }

  /** 返回首个完整字符的起始下标；缓冲从字符内部开始时跳过前导续字节。 */
  static int leadingCharacterBoundary(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    int index = 0;
    while (index < bytes.length && isContinuation(bytes[index])) {
      index++;
    }
    return index;
  }

  /** 返回 {@code [0, length)} 末尾不完整 UTF-8 序列的字节数；0 表示末尾已是完整字符边界。 */
  private static int incompleteSuffixLength(byte[] bytes, int length) {
    if (length == 0) {
      return 0;
    }
    int index = length - 1;
    int continuationCount = 0;
    while (index >= 0 && isContinuation(bytes[index])) {
      continuationCount++;
      index--;
    }
    if (index < 0) {
      return Math.min(length, 3);
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
