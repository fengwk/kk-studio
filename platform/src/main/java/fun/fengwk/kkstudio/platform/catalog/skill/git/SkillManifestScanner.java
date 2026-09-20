package fun.fengwk.kkstudio.platform.catalog.skill.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 扫描 Git commit 中根目录一层的 {@code <name>/SKILL.md}，严格解析并校验 Skill manifest。 */
public final class SkillManifestScanner {

  private SkillManifestScanner() {}

  /**
   * 扫描指定 commit 的根目录一层子目录，提取合法 SkillManifestEntry 并按 name 升序返回。
   *
   * @param repo 本地仓库
   * @param commitId commit 对象 id
   * @param packageName package 名称（用于日志与错误上下文）
   * @return 按 name 升序排序的 SkillManifestEntry 列表
   * @throws SkillGitException 当条目不符合契约、frontmatter 非法或 name 重复时
   */
  public static List<SkillManifestEntry> scan(
      Repository repo, ObjectId commitId, String packageName) {
    RevTree rootTree;
    try (RevWalk revWalk = new RevWalk(repo)) {
      RevCommit revCommit = revWalk.parseCommit(commitId);
      rootTree = revCommit.getTree();
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to parse commit "
              + commitId.getName()
              + " in package "
              + packageName
              + ": "
              + e.getMessage(),
          e);
    }

    List<SkillManifestEntry> entries = new ArrayList<>();
    Set<String> seenNames = new HashSet<>();

    try (TreeWalk treeWalk = new TreeWalk(repo)) {
      treeWalk.addTree(rootTree);
      treeWalk.setRecursive(false);

      while (treeWalk.next()) {
        String entryName = treeWalk.getNameString();
        validateEntrySegment(entryName);
        FileMode mode = treeWalk.getFileMode(0);

        if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
          // 根目录下的普通文件（如 README.md）忽略
          continue;
        }

        if (mode == FileMode.TREE) {
          scanSkillDirectory(repo, entryName, treeWalk.getObjectId(0), entries, seenNames);
        } else {
          // 根目录下 symlink、gitlink 等一律拒绝（越界保护）
          throw new SkillGitException(
              "Root entry '" + entryName + "' has forbidden file mode: " + mode);
        }
      }
    } catch (SkillGitException e) {
      throw e;
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to walk tree for commit "
              + commitId.getName()
              + " in package "
              + packageName
              + ": "
              + e.getMessage(),
          e);
    }

    entries.sort(Comparator.comparing(SkillManifestEntry::name, Comparator.naturalOrder()));
    return List.copyOf(entries);
  }

  private static void scanSkillDirectory(
      Repository repo,
      String dirName,
      ObjectId dirTreeId,
      List<SkillManifestEntry> entries,
      Set<String> seenNames)
      throws IOException {
    ObjectId skillMdBlobId = null;
    FileMode skillMdMode = null;

    try (TreeWalk subWalk = new TreeWalk(repo)) {
      subWalk.addTree(dirTreeId);
      subWalk.setRecursive(false);

      while (subWalk.next()) {
        String subName = subWalk.getNameString();
        validateEntrySegment(subName);
        if ("SKILL.md".equals(subName)) {
          skillMdBlobId = subWalk.getObjectId(0);
          skillMdMode = subWalk.getFileMode(0);
        }
      }
    }

    if (skillMdBlobId == null) {
      // 目录 <name> 若没有 <name>/SKILL.md 则忽略该目录
      return;
    }

    // 存在 SKILL.md，校验目录名必须是 canonical skill 名，且返回值与目录名完全一致
    try {
      String canonicalName = SkillNames.canonicalSkillName(dirName);
      if (!dirName.equals(canonicalName)) {
        throw new SkillGitException(
            "Skill directory '"
                + dirName
                + "' is not equal to canonical name '"
                + canonicalName
                + "'");
      }
    } catch (IllegalArgumentException e) {
      throw new SkillGitException(
          "Skill directory '" + dirName + "' is not a canonical skill name: " + e.getMessage(), e);
    }

    // SKILL.md 必须是普通文件 blob（FileMode REGULAR_FILE 或 EXECUTABLE_FILE）
    if (skillMdMode == FileMode.SYMLINK) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' is a symlink, which is forbidden");
    }
    if (skillMdMode == FileMode.GITLINK) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' is a gitlink (submodule), which is forbidden");
    }
    if (skillMdMode == FileMode.TREE) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' is a directory, not a regular file");
    }
    if (skillMdMode != FileMode.REGULAR_FILE && skillMdMode != FileMode.EXECUTABLE_FILE) {
      throw new SkillGitException(
          "Skill '" + dirName + "/SKILL.md' is not a regular file (mode=" + skillMdMode + ")");
    }

    ObjectLoader loader = repo.open(skillMdBlobId, Constants.OBJ_BLOB);
    byte[] bytes = loader.getBytes();
    String content = new String(bytes, StandardCharsets.UTF_8);

    SkillManifestEntry entry = parseFrontmatter(dirName, content);

    if (!seenNames.add(entry.name())) {
      throw new SkillGitException("Duplicate skill name '" + entry.name() + "' found in package");
    }

    entries.add(entry);
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
