package fun.fengwk.kkstudio.platform.catalog.skill.git;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 扫描已物化目录中根目录一层的 {@code <name>/SKILL.md}，严格解析并校验 Skill manifest。 */
public final class SkillManifestScanner {

  private SkillManifestScanner() {}

  /**
   * 扫描物化目录的根目录一层子目录，提取合法 SkillManifestEntry 并按 name 升序返回。
   *
   * @param root 本地物化目录根路径
   * @param packageName package 名称（用于日志与错误上下文）
   * @return 按 name 升序排序的 SkillManifestEntry 列表
   * @throws SkillGitException 当条目不符合契约、frontmatter 非法或 name 重复时
   */
  public static List<SkillManifestEntry> scan(Path root, String packageName) {
    if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new SkillGitException("Root directory not found for package: " + packageName);
    }

    List<SkillManifestEntry> entries = new ArrayList<>();
    Set<String> seenNames = new HashSet<>();

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
      for (Path entry : stream) {
        String name = entry.getFileName().toString();
        validateEntrySegment(name);

        BasicFileAttributes attrs;
        try {
          attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
          throw new SkillGitException(
              "Failed to read attributes for entry '" + name + "' in package " + packageName, e);
        }

        if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
          throw new SkillGitException("Root entry '" + name + "' has forbidden file type");
        }

        if (attrs.isRegularFile()) {
          // 根目录下的普通文件（如 README.md、.kkstudio-commit）忽略
          continue;
        }

        Path skillMd = entry.resolve("SKILL.md");
        BasicFileAttributes skillMdAttrs;
        try {
          skillMdAttrs =
              Files.readAttributes(skillMd, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
          // SKILL.md missing -> ignore the directory
          continue;
        } catch (IOException e) {
          throw new SkillGitException(
              "Failed to read attributes for '" + name + "/SKILL.md' in package " + packageName, e);
        }

        if (!skillMdAttrs.isRegularFile()) {
          throw new SkillGitException("Skill '" + name + "/SKILL.md' is not a regular file");
        }

        try {
          String canonicalName = SkillNames.canonicalSkillName(name);
          if (!name.equals(canonicalName)) {
            throw new SkillGitException(
                "Skill directory '"
                    + name
                    + "' is not equal to canonical name '"
                    + canonicalName
                    + "'");
          }
        } catch (IllegalArgumentException e) {
          throw new SkillGitException(
              "Skill directory '" + name + "' is not a canonical skill name: " + e.getMessage(), e);
        }

        String content;
        try {
          content = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
          throw new SkillGitException(
              "Failed to read content for '" + name + "/SKILL.md' in package " + packageName, e);
        }

        SkillManifestEntry manifestEntry = parseFrontmatter(name, content);
        if (!seenNames.add(manifestEntry.name())) {
          throw new SkillGitException(
              "Duplicate skill name '" + manifestEntry.name() + "' found in package");
        }

        entries.add(manifestEntry);
      }
    } catch (SkillGitException e) {
      throw e;
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to scan manifest directory for package " + packageName + ": " + e.getMessage(),
          e);
    }

    entries.sort(Comparator.comparing(SkillManifestEntry::name, Comparator.naturalOrder()));
    return List.copyOf(entries);
  }

  static SkillManifestEntry parseFrontmatter(String dirName, String content) {
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

  static void validateEntrySegment(String name) {
    if (name == null || name.isEmpty()) {
      throw new SkillGitException("Invalid empty entry name in repository");
    }
    if (".".equals(name) || "..".equals(name)) {
      throw new SkillGitException("Invalid entry name '" + name + "' in repository");
    }
    if (name.contains("/") || name.contains("\\")) {
      throw new SkillGitException("Invalid entry name containing path separators: '" + name + "'");
    }
    if (name.codePoints().anyMatch(Character::isISOControl)) {
      throw new SkillGitException("Entry name contains control characters: '" + name + "'");
    }
  }
}
