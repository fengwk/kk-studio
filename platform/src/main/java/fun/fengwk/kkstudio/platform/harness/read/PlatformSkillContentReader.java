package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.Objects;

/**
 * 平台侧 Skill 内容读取器。
 *
 * <p>按 {@code kkstudio:/skills/<package>/<skill>/<relativePath>} 读取已发布 Skill 的内容。 校验 package
 * 存在、skill 在 manifest 中存在、相对路径合法后，确保 exact commit 存在于 Git cache 并读取目标文件。
 */
public class PlatformSkillContentReader {

  private final SkillCatalogQueryService queryService;
  private final SkillGitCache gitCache;

  public PlatformSkillContentReader(SkillCatalogQueryService queryService, SkillGitCache gitCache) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.gitCache = Objects.requireNonNull(gitCache, "gitCache");
  }

  /**
   * 读取指定 Skill Package 下某个 Skill 的文件字节。
   *
   * @param packageName Package 名称，非空
   * @param skillName Skill 名称，非空
   * @param relativePath 相对文件路径，非空
   * @return 文件的原始字节
   * @throws PlatformReadException Package 不存在、Skill 不存在、路径非法或 Git 读取失败时抛出
   */
  public byte[] readSkillFile(String packageName, String skillName, String relativePath) {
    if (packageName == null || packageName.isBlank()) {
      throw new PlatformReadException("packageName must not be blank");
    }
    if (skillName == null || skillName.isBlank()) {
      throw new PlatformReadException("skillName must not be blank");
    }
    validateRelativePath(relativePath);

    SkillPackage pkg = queryService.getPackage(packageName);
    if (pkg == null) {
      throw new PlatformReadException("skill package not found: " + packageName);
    }

    SkillManifestEntry skill = pkg.findSkill(skillName);
    if (skill == null) {
      throw new PlatformReadException("skill not found: " + packageName + "/" + skillName);
    }

    String currentCommit = pkg.getCurrentCommit();
    if (currentCommit == null || currentCommit.isBlank()) {
      throw new PlatformReadException("skill package has no published commit: " + packageName);
    }

    try {
      gitCache.ensureCommit(packageName, pkg.getRepositoryUrl(), currentCommit);
    } catch (SkillGitException e) {
      throw new PlatformReadException("skill content is unavailable: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new PlatformReadException("skill content is unavailable: " + e.getMessage(), e);
    }

    String repoPath =
        relativePath.startsWith(skillName + "/") ? relativePath : skillName + "/" + relativePath;

    try {
      return gitCache.readFile(packageName, currentCommit, repoPath);
    } catch (SkillGitException e) {
      throw new PlatformReadException("failed to read skill file: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new PlatformReadException("failed to read skill file: " + e.getMessage(), e);
    }
  }

  private static void validateRelativePath(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new PlatformReadException("relative path must not be blank");
    }
    if (relativePath.startsWith("/")) {
      throw new PlatformReadException("relative path must not start with /: " + relativePath);
    }
    if (relativePath.contains("\\")) {
      throw new PlatformReadException("relative path must not contain \\: " + relativePath);
    }
    for (int i = 0; i < relativePath.length(); i++) {
      char c = relativePath.charAt(i);
      if (Character.isISOControl(c)) {
        throw new PlatformReadException(
            "relative path must not contain control characters: " + relativePath);
      }
    }
    String[] segments = relativePath.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new PlatformReadException("relative path contains invalid segment: " + relativePath);
      }
    }
  }
}
