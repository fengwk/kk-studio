package fun.fengwk.kkstudio.platform.catalog.skill.git;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillNames;

import java.util.Map;

/** SKILL.md YAML frontmatter 的严格解析：name 必须等于目录名，description 去边缘空白后 1–1024 字符。 */
public final class SkillManifestScanner {

  private static final String DELIMITER = "---";

  private SkillManifestScanner() {}

  /** 解析技能文件的 YAML frontmatter；调用方负责校验目录名和输入大小。 */
  public static SkillManifestEntry parseFrontmatter(String dirName, String content) {
    if (content == null) {
      throw new SkillGitException("Skill '" + dirName + "/SKILL.md' content is null");
    }
    int start = frontmatterStart(dirName, content);
    int end = frontmatterEnd(dirName, content, start);
    Map<?, ?> frontmatter = loadMapping(dirName, content.substring(start, end));
    String name = requireName(dirName, frontmatter);
    return new SkillManifestEntry(name, canonicalDescription(dirName, frontmatter));
  }

  /** 返回首行定界符之后的正文偏移；CRLF 与 LF 都接受，其他任何开头都拒绝。 */
  private static int frontmatterStart(String dirName, String content) {
    if (content.startsWith(DELIMITER + "\r\n")) {
      return DELIMITER.length() + 2;
    }
    if (content.startsWith(DELIMITER + "\n")) {
      return DELIMITER.length() + 1;
    }
    throw new SkillGitException(
        "Skill '" + dirName + "/SKILL.md' does not start with YAML frontmatter delimiter '---'");
  }

  /** 找到独占一行的结束定界符；行尾允许一个 CR，未闭合时拒绝。 */
  private static int frontmatterEnd(String dirName, String content, int start) {
    int current = start;
    int length = content.length();
    while (current < length) {
      int nextNewline = content.indexOf('\n', current);
      int lineEnd = nextNewline == -1 ? length : nextNewline;
      int lineContentEnd = lineEnd;
      if (lineContentEnd > current && content.charAt(lineContentEnd - 1) == '\r') {
        lineContentEnd--;
      }
      if (DELIMITER.equals(content.substring(current, lineContentEnd))) {
        return current;
      }
      if (nextNewline == -1) {
        break;
      }
      current = nextNewline + 1;
    }
    throw new SkillGitException(
        "Skill '" + dirName + "/SKILL.md' frontmatter is not closed with '---'");
  }

  private static Map<?, ?> loadMapping(String dirName, String yamlText) {
    Object parsed;
    try {
      parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
    } catch (Exception e) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' failed to parse frontmatter YAML: " + e.getMessage(),
          e);
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter must be a YAML mapping");
    }
    return map;
  }

  private static String requireName(String dirName, Map<?, ?> frontmatter) {
    if (!(frontmatter.get("name") instanceof String name)) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter missing or invalid 'name'");
    }
    if (!dirName.equals(name)) {
      throw new SkillGitException(
          "Skill '"
              + dirName
              + "/SKILL.md' frontmatter name '"
              + name
              + "' does not match directory name '"
              + dirName
              + "'");
    }
    return name;
  }

  private static String canonicalDescription(String dirName, Map<?, ?> frontmatter) {
    if (!(frontmatter.get("description") instanceof String description)) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter missing or invalid 'description'");
    }
    try {
      return SkillNames.canonicalDescription(description);
    } catch (IllegalArgumentException e) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' invalid description: " + e.getMessage(), e);
    }
  }
}
