package fun.fengwk.kkstudio.platform.catalog.skill.git;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillNames;

import java.util.Map;

/** SKILL.md YAML frontmatter 的严格解析：name 必须等于目录名，description 去边缘空白后 1–1024 字符。 */
public final class SkillManifestScanner {

  private SkillManifestScanner() {}

  /** 解析技能文件的 YAML frontmatter；调用方负责校验目录名和输入大小。 */
  public static SkillManifestEntry parseFrontmatter(String dirName, String content) {
    if (content == null) {
      throw new SkillGitException("Skill '" + dirName + "/SKILL.md' content is null");
    }

    int offset;
    if (content.startsWith("---\r\n")) {
      offset = 5;
    } else if (content.startsWith("---\n")) {
      offset = 4;
    } else {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' does not start with YAML frontmatter delimiter '---'");
    }

    int current = offset;
    int length = content.length();
    int frontmatterEnd = -1;

    while (current < length) {
      int nextNewline = content.indexOf('\n', current);
      int lineEnd = (nextNewline == -1) ? length : nextNewline;
      int lineContentEnd = lineEnd;
      if (lineContentEnd > current && content.charAt(lineContentEnd - 1) == '\r') {
        lineContentEnd--;
      }
      String line = content.substring(current, lineContentEnd);
      if ("---".equals(line)) {
        frontmatterEnd = current;
        break;
      }
      if (nextNewline == -1) {
        break;
      }
      current = nextNewline + 1;
    }

    if (frontmatterEnd == -1) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter is not closed with '---'");
    }

    String yamlText = content.substring(offset, frontmatterEnd);

    LoaderOptions loaderOptions = new LoaderOptions();
    Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
    Object parsed;
    try {
      parsed = yaml.load(yamlText);
    } catch (Exception e) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' failed to parse frontmatter YAML: " + e.getMessage(),
          e);
    }

    if (!(parsed instanceof Map<?, ?> map)) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter must be a YAML mapping");
    }

    Object nameObj = map.get("name");
    if (!(nameObj instanceof String name)) {
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

    Object descObj = map.get("description");
    if (!(descObj instanceof String desc)) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' frontmatter missing or invalid 'description'");
    }

    String canonicalDesc;
    try {
      canonicalDesc = SkillNames.canonicalDescription(desc);
    } catch (IllegalArgumentException e) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' invalid description: " + e.getMessage(), e);
    }

    return new SkillManifestEntry(name, canonicalDesc);
  }
}
