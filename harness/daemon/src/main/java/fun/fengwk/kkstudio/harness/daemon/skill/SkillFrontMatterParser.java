package fun.fengwk.kkstudio.harness.daemon.skill;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确定性解析 SKILL.md 顶部 YAML front matter 的 {@code name}/{@code description}。
 *
 * <p>不引入 YAML 依赖：要求文件以 {@code ---} 开头，下一处独立 {@code ---} 行结束；仅支持单行 {@code key: value}（可选引号）。缺失或空白
 * name/description 时失败。
 */
public final class SkillFrontMatterParser {

  private SkillFrontMatterParser() {}

  public static SkillFrontMatter parse(String content) {
    if (content == null) {
      throw new IllegalArgumentException("SKILL.md content must not be null");
    }
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    if (!normalized.startsWith("---\n") && !normalized.equals("---")) {
      throw new IllegalArgumentException(
          "SKILL.md must start with YAML front matter delimiter ---");
    }
    int bodyStart = normalized.indexOf("\n---\n", 3);
    String matterBlock;
    if (bodyStart >= 0) {
      matterBlock = normalized.substring(4, bodyStart);
    } else if (normalized.endsWith("\n---")) {
      matterBlock = normalized.substring(4, normalized.length() - 4);
    } else {
      throw new IllegalArgumentException("SKILL.md front matter is not closed by ---");
    }

    Map<String, String> fields = new LinkedHashMap<>();
    for (String rawLine : matterBlock.split("\n", -1)) {
      String line = rawLine.stripTrailing();
      if (line.isBlank()) {
        continue;
      }
      if (Character.isWhitespace(line.charAt(0))) {
        throw new IllegalArgumentException(
            "SKILL.md front matter does not support indented continuation lines");
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new IllegalArgumentException("SKILL.md front matter line must be 'key: value'");
      }
      String key = line.substring(0, colon).strip();
      String value = unquote(line.substring(colon + 1).strip());
      if (key.isEmpty()) {
        throw new IllegalArgumentException("SKILL.md front matter key must not be blank");
      }
      // First occurrence wins for determinism; later duplicates are ignored.
      fields.putIfAbsent(key, value);
    }

    String name = fields.get("name");
    String description = fields.get("description");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("SKILL.md front matter missing non-blank name");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("SKILL.md front matter missing non-blank description");
    }
    return new SkillFrontMatter(name, description);
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }
}
