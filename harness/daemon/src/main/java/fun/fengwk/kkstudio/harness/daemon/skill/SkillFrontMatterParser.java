package fun.fengwk.kkstudio.harness.daemon.skill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 针对 Agent Skills {@code SKILL.md} 的轻量前置元数据（front matter）解析器。
 *
 * <p>仅解析顶层必需的 {@code name} 与 {@code description} 字段，支持普通标量、单/双引号标量与缩进的 {@code |} / {@code >}
 * 块标量；安全忽略未知顶层字段，不回显文档敏感内容。
 */
final class SkillFrontMatterParser {

  private SkillFrontMatterParser() {}

  static DaemonSkill parse(Path skillDir, String source) {
    Path skillFile = skillDir.resolve("SKILL.md");
    if (!Files.isRegularFile(skillFile)) {
      throw new IllegalArgumentException("SKILL.md not found in " + skillDir);
    }

    String rawText;
    try {
      byte[] bytes = Files.readAllBytes(skillFile);
      rawText =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(
          "cannot load SKILL.md from " + skillDir + " (" + source + "): invalid UTF-8 encoding",
          error);
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot load SKILL.md from " + skillDir + " (" + source + "): read error", error);
    }

    if (rawText.startsWith("\uFEFF")) {
      rawText = rawText.substring(1);
    }

    List<String> lines = splitLines(rawText);
    if (lines.isEmpty() || !lines.getFirst().trim().equals("---")) {
      throw new IllegalArgumentException(
          "cannot load SKILL.md from "
              + skillDir
              + " ("
              + source
              + "): missing front matter opening delimiter");
    }

    int closingIndex = -1;
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().equals("---")) {
        closingIndex = i;
        break;
      }
    }

    if (closingIndex == -1) {
      throw new IllegalArgumentException(
          "cannot load SKILL.md from "
              + skillDir
              + " ("
              + source
              + "): missing front matter closing delimiter");
    }

    String name = null;
    String description = null;
    Set<String> seenKeys = new HashSet<>();

    int i = 1;
    while (i < closingIndex) {
      String line = lines.get(i);
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        i++;
        continue;
      }

      if (line.startsWith(" ") || line.startsWith("\t") || !line.contains(":")) {
        throw new IllegalArgumentException(
            "cannot load SKILL.md from "
                + skillDir
                + " ("
                + source
                + "): malformed front matter entry");
      }

      int colonIdx = line.indexOf(':');
      String key = line.substring(0, colonIdx).trim();
      if (key.isEmpty()) {
        throw new IllegalArgumentException(
            "cannot load SKILL.md from "
                + skillDir
                + " ("
                + source
                + "): empty key in front matter");
      }

      if (!seenKeys.add(key)) {
        throw new IllegalArgumentException(
            "cannot load SKILL.md from "
                + skillDir
                + " ("
                + source
                + "): duplicate front matter field '"
                + key
                + "'");
      }

      String afterColon = line.substring(colonIdx + 1).trim();

      if (afterColon.startsWith("|") || afterColon.startsWith(">")) {
        char style = afterColon.charAt(0);
        List<String> blockLines = new ArrayList<>();
        i++;
        while (i < closingIndex) {
          String nextLine = lines.get(i);
          if (nextLine.trim().equals("---")) {
            break;
          }
          if (nextLine.trim().isEmpty() || nextLine.startsWith(" ") || nextLine.startsWith("\t")) {
            blockLines.add(nextLine);
            i++;
          } else {
            break;
          }
        }
        String blockValue = formatBlockScalar(blockLines, style);
        if ("name".equals(key)) {
          name = blockValue;
        } else if ("description".equals(key)) {
          description = blockValue;
        }
      } else if (afterColon.isEmpty()) {
        List<String> continuationLines = new ArrayList<>();
        int peek = i + 1;
        while (peek < closingIndex) {
          String nextLine = lines.get(peek);
          if (nextLine.trim().equals("---")) {
            break;
          }
          if (nextLine.startsWith(" ") || nextLine.startsWith("\t")) {
            continuationLines.add(nextLine);
            peek++;
          } else {
            break;
          }
        }
        if (!continuationLines.isEmpty()) {
          i = peek;
          String blockValue = formatBlockScalar(continuationLines, '|');
          if ("name".equals(key)) {
            name = blockValue;
          } else if ("description".equals(key)) {
            description = blockValue;
          }
        } else {
          if ("name".equals(key)) {
            name = "";
          } else if ("description".equals(key)) {
            description = "";
          }
          i++;
        }
      } else {
        String scalarValue = parseScalar(afterColon);
        if ("name".equals(key)) {
          name = scalarValue;
        } else if ("description".equals(key)) {
          description = scalarValue;
        }
        i++;
      }
    }

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "invalid SKILL.md metadata at " + skillDir + " (" + source + "): missing non-blank name");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException(
          "invalid SKILL.md metadata at "
              + skillDir
              + " ("
              + source
              + "): missing non-blank description");
    }

    StringBuilder bodyBuilder = new StringBuilder();
    for (int lineIdx = closingIndex + 1; lineIdx < lines.size(); lineIdx++) {
      if (!bodyBuilder.isEmpty()) {
        bodyBuilder.append("\n");
      }
      bodyBuilder.append(lines.get(lineIdx));
    }
    String body = bodyBuilder.toString().trim();

    return new DaemonSkill(name.trim(), description.trim(), body);
  }

  private static String parseScalar(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return unescapeDoubleQuotes(trimmed.substring(1, trimmed.length() - 1));
    }
    if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
      return unescapeSingleQuotes(trimmed.substring(1, trimmed.length() - 1));
    }
    return trimmed;
  }

  private static String unescapeDoubleQuotes(String text) {
    StringBuilder sb = new StringBuilder();
    boolean escaping = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaping) {
        switch (c) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case '\"' -> sb.append('\"');
          case '\\' -> sb.append('\\');
          default -> sb.append(c);
        }
        escaping = false;
      } else if (c == '\\') {
        escaping = true;
      } else {
        sb.append(c);
      }
    }
    if (escaping) {
      sb.append('\\');
    }
    return sb.toString();
  }

  private static String unescapeSingleQuotes(String text) {
    return text.replace("''", "'");
  }

  private static String formatBlockScalar(List<String> lines, char style) {
    int lastNonEmpty = lines.size() - 1;
    while (lastNonEmpty >= 0 && lines.get(lastNonEmpty).trim().isEmpty()) {
      lastNonEmpty--;
    }
    if (lastNonEmpty < 0) {
      return "";
    }

    int minIndent = Integer.MAX_VALUE;
    for (int idx = 0; idx <= lastNonEmpty; idx++) {
      String line = lines.get(idx);
      if (line.trim().isEmpty()) {
        continue;
      }
      int indent = 0;
      while (indent < line.length()
          && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
        indent++;
      }
      if (indent < minIndent) {
        minIndent = indent;
      }
    }
    if (minIndent == Integer.MAX_VALUE) {
      minIndent = 0;
    }

    List<String> unindented = new ArrayList<>();
    for (int idx = 0; idx <= lastNonEmpty; idx++) {
      String line = lines.get(idx);
      if (line.trim().isEmpty()) {
        unindented.add("");
      } else if (line.length() >= minIndent) {
        unindented.add(line.substring(minIndent));
      } else {
        unindented.add(line.trim());
      }
    }

    if (style == '|') {
      return String.join("\n", unindented).trim();
    } else {
      StringBuilder sb = new StringBuilder();
      boolean inParagraph = false;
      for (String line : unindented) {
        if (line.isEmpty()) {
          sb.append("\n\n");
          inParagraph = false;
        } else {
          if (inParagraph) {
            sb.append(" ");
          }
          sb.append(line);
          inParagraph = true;
        }
      }
      return sb.toString().trim();
    }
  }

  private static List<String> splitLines(String text) {
    List<String> result = new ArrayList<>();
    int len = text.length();
    int start = 0;
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        result.add(text.substring(start, i));
        if (i + 1 < len && text.charAt(i + 1) == '\n') {
          i++;
        }
        start = i + 1;
      } else if (c == '\n') {
        result.add(text.substring(start, i));
        start = i + 1;
      }
    }
    if (start <= len) {
      result.add(text.substring(start));
    }
    return result;
  }
}
