package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.common.hash.Sha256;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * terminal textual projection 的测量工具：UTF-8 字节数、物理行数、raw preview 前缀与严格 Unicode 校验。
 *
 * <p>测量从不分配整段编码副本，因此超限错误可以报告精确字节数；非法代理项一律拒绝，不做静默替换。
 */
final class TerminalTextMetrics {

  private TerminalTextMetrics() {}

  /** 严格计算物理行数：空文本为 0；否则为 LF 数量加上未以 LF 结尾时的最后一行。 */
  static long countPhysicalLines(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    long lfCount = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lfCount++;
      }
    }
    return lfCount + (text.charAt(text.length() - 1) != '\n' ? 1 : 0);
  }

  /** 提取 UTF-8 安全且不拆行/码点的 raw preview 前缀（&le; 2 KiB 且 &le; 20 物理行）。 只保留前缀，无 tail，无截断标记。 */
  static String extractRawPreview(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    int lines = 0;
    int bytes = 0;
    boolean lineStarted = false;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int charCount = Character.charCount(cp);
      String cpStr = text.substring(i, i + charCount);
      int cpBytes = utf8Bytes(cp);
      if (bytes + cpBytes > ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES) {
        break;
      }
      if (cp == '\n') {
        lines++;
        sb.append(cpStr);
        bytes += cpBytes;
        lineStarted = false;
        if (lines >= ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_LINES) {
          break;
        }
      } else {
        if (!lineStarted) {
          if (lines >= ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_LINES) {
            break;
          }
          lineStarted = true;
        }
        sb.append(cpStr);
        bytes += cpBytes;
      }
      i += charCount;
    }
    return sb.toString();
  }

  private static int utf8Bytes(int codePoint) {
    if (codePoint <= 0x7F) {
      return 1;
    }
    if (codePoint <= 0x7FF) {
      return 2;
    }
    if (codePoint <= 0xFFFF) {
      return 3;
    }
    return 4;
  }

  static long utf8Length(String text) {
    long bytes = 0;
    for (int index = 0; index < text.length(); ) {
      char current = text.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
          throw new IllegalArgumentException("tool output must be valid Unicode");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException("tool output must be valid Unicode");
      }
      int codePoint = text.codePointAt(index);
      bytes += utf8Bytes(codePoint);
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  static void validateStrictUnicode(String text, String name) {
    try {
      utf8Length(text);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(name + " must be valid Unicode", invalid);
    }
  }

  static String decodeStrictUtf8(byte[] bytes, String context) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException(context + " must be valid UTF-8");
    }
  }

  static String sha256Utf8(String text, String name) {
    MessageDigest digest = Sha256.newDigest();
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer chars = CharBuffer.wrap(text);
    ByteBuffer bytes = ByteBuffer.allocate(8192);
    try {
      while (true) {
        CoderResult result = encoder.encode(chars, bytes, true);
        if (result.isError()) {
          result.throwException();
        }
        bytes.flip();
        digest.update(bytes);
        bytes.clear();
        if (result.isUnderflow()) {
          break;
        }
      }
      CoderResult flush = encoder.flush(bytes);
      if (flush.isError()) {
        flush.throwException();
      }
      bytes.flip();
      digest.update(bytes);
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(name + " must be valid Unicode", error);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
