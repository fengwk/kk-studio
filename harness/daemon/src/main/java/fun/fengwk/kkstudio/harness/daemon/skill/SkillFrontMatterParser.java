package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 针对 Agent Skills {@code SKILL.md} 的轻量前置元数据（front matter）解析器。
 *
 * <p>仅解析顶层必需的 {@code name} 与 {@code description} 字段，支持普通标量、单/双引号标量与缩进的 {@code |} / {@code >}
 * 块标量；安全忽略未知顶层字段。失败以 {@link SkillParseException} 的短原因表达，绝不回显正文内容。
 *
 * <p>{@code contentRevision} 是 SKILL.md <b>原始字节</b>的 lowercase SHA-256，因此正文任何字节变化都改变 revision。
 */
final class SkillFrontMatterParser {

  private SkillFrontMatterParser() {}

  static DaemonSkill parse(Path skillDir, String source) {
    Path skillFile = skillDir.resolve("SKILL.md");
    byte[] rawBytes;
    try {
      rawBytes = Files.readAllBytes(skillFile);
    } catch (IOException error) {
      throw new SkillParseException("SKILL.md is not readable (" + source + ")");
    }
    String rawText = decodeUtf8(rawBytes, source);
    if (rawText.startsWith("\uFEFF")) {
      rawText = rawText.substring(1);
    }

    List<String> lines = splitLines(rawText);
    if (lines.isEmpty() || !lines.getFirst().trim().equals("---")) {
      throw new SkillParseException("missing front matter opening delimiter");
    }

    int closingIndex = -1;
    for (int index = 1; index < lines.size(); index++) {
      if (lines.get(index).trim().equals("---")) {
        closingIndex = index;
        break;
      }
    }
    if (closingIndex == -1) {
      throw new SkillParseException("missing front matter closing delimiter");
    }

    String name = null;
    String description = null;
    Set<String> seenKeys = new HashSet<>();
    int lineIndex = 1;
    while (lineIndex < closingIndex) {
      String line = lines.get(lineIndex);
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        lineIndex++;
        continue;
      }
      if (line.startsWith(" ") || line.startsWith("\t") || !line.contains(":")) {
        throw new SkillParseException("malformed front matter entry");
      }

      int colonIndex = line.indexOf(':');
      String key = line.substring(0, colonIndex).trim();
      if (key.isEmpty()) {
        throw new SkillParseException("empty front matter key");
      }
      if (!seenKeys.add(key)) {
        throw new SkillParseException("duplicate front matter field");
      }

      String afterColon = line.substring(colonIndex + 1).trim();
      if (afterColon.startsWith("|") || afterColon.startsWith(">")) {
        char style = afterColon.charAt(0);
        List<String> blockLines = new ArrayList<>();
        lineIndex++;
        while (lineIndex < closingIndex) {
          String nextLine = lines.get(lineIndex);
          if (nextLine.trim().equals("---")) {
            break;
          }
          if (nextLine.trim().isEmpty() || nextLine.startsWith(" ") || nextLine.startsWith("\t")) {
            blockLines.add(nextLine);
            lineIndex++;
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
        int peek = lineIndex + 1;
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
        String value;
        if (!continuationLines.isEmpty()) {
          lineIndex = peek;
          value = formatBlockScalar(continuationLines, '|');
        } else {
          value = "";
          lineIndex++;
        }
        if ("name".equals(key)) {
          name = value;
        } else if ("description".equals(key)) {
          description = value;
        }
      } else {
        String scalarValue = parseScalar(afterColon);
        if ("name".equals(key)) {
          name = scalarValue;
        } else if ("description".equals(key)) {
          description = scalarValue;
        }
        lineIndex++;
      }
    }

    if (name == null || name.isBlank()) {
      throw new SkillParseException("missing non-blank front matter name");
    }
    if (description == null || description.isBlank()) {
      throw new SkillParseException("missing non-blank front matter description");
    }
    name = name.trim();
    description = description.trim();
    // 超限元数据是单个坏 Skill 的事实，必须在解析期以结构性原因失败，才能变成有界诊断而不是中止整个来源的发布。
    if (name.length() > DaemonSkillDescriptor.MAX_NAME_CHARS) {
      throw new SkillParseException("front matter name exceeds the supported length");
    }
    if (description.length() > DaemonSkillDescriptor.MAX_DESCRIPTION_CHARS) {
      throw new SkillParseException("front matter description exceeds the supported length");
    }

    StringBuilder bodyBuilder = new StringBuilder();
    for (int index = closingIndex + 1; index < lines.size(); index++) {
      if (!bodyBuilder.isEmpty()) {
        bodyBuilder.append("\n");
      }
      bodyBuilder.append(lines.get(index));
    }
    String body = bodyBuilder.toString().trim();
    return new DaemonSkill(name, description, skillDir, contentRevision(rawBytes, source), body);
  }

  /** 内容 revision：SKILL.md 原始字节的 lowercase SHA-256（十六进制）。 */
  static String contentRevision(byte[] rawBytes, String source) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawBytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable for " + source, error);
    }
  }

  private static String decodeUtf8(byte[] rawBytes, String source) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(rawBytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new SkillParseException("SKILL.md is not valid UTF-8 (" + source + ")");
    }
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
    for (int index = 0; index <= lastNonEmpty; index++) {
      String line = lines.get(index);
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
    for (int index = 0; index <= lastNonEmpty; index++) {
      String line = lines.get(index);
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
    }
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
