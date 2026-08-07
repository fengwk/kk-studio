package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 对 tool 输出应用共享的有界预览 + 完整 resource 策略。 */
final class OutputLimiter {

  private OutputLimiter() {}

  static List<ToolContent> limit(byte[] bytes, String mediaType, CodingToolsConfig config)
      throws IOException {
    boolean binary = isBinary(bytes);
    String text =
        binary
            ? "[Binary output; complete bytes are attached as a resource.]"
            : new String(bytes, StandardCharsets.UTF_8);
    if (binary || exceeds(text, bytes.length, config)) {
      List<ToolContent> contents = new ArrayList<>();
      contents.add(
          new TextToolContent(binary ? text : preview(text, config) + truncationHint(bytes, text)));
      contents.add(new ResourceToolContent(config.resourceStore().store(bytes, mediaType)));
      return contents;
    }
    return List.of(new TextToolContent(text));
  }

  static boolean isBinary(byte[] bytes) {
    int scan = Math.min(bytes.length, 8192);
    for (int index = 0; index < scan; index++) {
      int value = bytes[index] & 0xff;
      if (value == 0 || (value < 0x09) || (value > 0x0d && value < 0x20)) {
        return true;
      }
    }
    return false;
  }

  static boolean exceeds(String text, int byteCount, CodingToolsConfig config) {
    return byteCount > config.previewMaxBytes() || lineCount(text) > config.previewMaxLines();
  }

  static String preview(String text, CodingToolsConfig config) {
    StringBuilder result = new StringBuilder();
    int byteCount = 0;
    int lines = 0;
    for (String line : text.split("\\n", -1)) {
      if (lines >= config.previewMaxLines()) {
        break;
      }
      String prefix = lines == 0 ? "" : "\n";
      byte[] lineBytes = (prefix + line).getBytes(StandardCharsets.UTF_8);
      if (byteCount + lineBytes.length > config.previewMaxBytes()) {
        int remaining =
            Math.max(
                0,
                config.previewMaxBytes()
                    - byteCount
                    - prefix.getBytes(StandardCharsets.UTF_8).length);
        result.append(prefix).append(prefixWithinUtf8Limit(line, remaining));
        break;
      }
      result.append(prefix).append(line);
      byteCount += lineBytes.length;
      lines++;
    }
    return result.toString();
  }

  private static String prefixWithinUtf8Limit(String value, int maximumBytes) {
    StringBuilder result = new StringBuilder();
    int used = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      String character = new String(Character.toChars(codePoint));
      int size = character.getBytes(StandardCharsets.UTF_8).length;
      if (used + size > maximumBytes) {
        break;
      }
      result.append(character);
      used += size;
      offset += Character.charCount(codePoint);
    }
    return result.toString();
  }

  private static String truncationHint(byte[] bytes, String text) {
    return "\n\n[Output truncated; complete output ("
        + bytes.length
        + " bytes, "
        + lineCount(text)
        + ") is attached as a resource.]";
  }

  private static int lineCount(String text) {
    return text.isEmpty() ? 0 : text.split("\\n", -1).length;
  }
}
