package fun.fengwk.kkstudio.platform.harness.read;

import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 平台侧 Skill 内容读取器。
 *
 * <p>按 {@code vfs:/skills/<package>/<skill>/<relativePath>} 读取已发布 Skill 的内容：校验 package 存在、skill 在
 * manifest 中存在、相对路径合法后，按当前版本归档的条目索引做范围读取，只解压目标文件。
 */
public class PlatformSkillContentReader {

  private final SkillPackageRepository skillPackageRepository;
  private final SkillArchiveFileReader archiveFileReader;

  public PlatformSkillContentReader(
      SkillPackageRepository skillPackageRepository, SkillArchiveFileReader archiveFileReader) {
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.archiveFileReader = Objects.requireNonNull(archiveFileReader, "archiveFileReader");
  }

  /**
   * 列出全部已发布包的只读摘要。摘要只含目录展示所需的包名、描述与技能清单，不含 token 或仓库地址。
   *
   * @return 按包名升序排列的包摘要
   */
  public List<PackageSummary> listPackages() {
    return skillPackageRepository.listPackages().stream()
        .map(
            pkg ->
                new PackageSummary(
                    pkg.packageName(),
                    pkg.description(),
                    pkg.skills() == null ? List.of() : List.copyOf(pkg.skills())))
        .toList();
  }

  /**
   * 列出指定包 manifest 中的技能。
   *
   * @param packageName 包名
   * @return 按 manifest 顺序排列的技能
   * @throws PlatformReadException 包不存在
   */
  public List<SkillManifestEntry> listSkills(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      throw new PlatformReadException("packageName must not be blank");
    }
    SkillPackage pkg = skillPackageRepository.getPackage(packageName);
    if (pkg == null) {
      throw new PlatformReadException("skill package not found: " + packageName);
    }
    return pkg.skills() == null ? List.of() : List.copyOf(pkg.skills());
  }

  /**
   * 列出某个技能归档内的文件；条目只来自服务端生成的 {@code storage_blob.archive_index}，不访问 Git 或对象存储。
   *
   * @param packageName 包名
   * @param skillName 技能名
   * @return 相对技能目录的路径与解压后字节数，按路径升序
   * @throws PlatformReadException 包或技能不存在，或归档没有服务端索引
   */
  public List<SkillFileEntry> listSkillFiles(String packageName, String skillName) {
    if (packageName == null || packageName.isBlank()) {
      throw new PlatformReadException("packageName must not be blank");
    }
    if (skillName == null || skillName.isBlank()) {
      throw new PlatformReadException("skillName must not be blank");
    }
    SkillPackage pkg = skillPackageRepository.getPackage(packageName);
    if (pkg == null) {
      throw new PlatformReadException("skill package not found: " + packageName);
    }
    if (pkg.findSkill(skillName) == null) {
      throw new PlatformReadException("skill not found: " + packageName + "/" + skillName);
    }
    String prefix = skillName + "/";
    try {
      return archiveFileReader.listEntries(pkg.artifactBlobId()).stream()
          .filter(entry -> entry.path().startsWith(prefix))
          .map(entry -> new SkillFileEntry(entry.path().substring(prefix.length()), entry.size()))
          .sorted(Comparator.comparing(SkillFileEntry::path))
          .toList();
    } catch (RuntimeException e) {
      throw new PlatformReadException("failed to list skill files: " + e.getMessage(), e);
    }
  }

  /** 技能目录内的单个文件：相对技能根目录的路径与解压后字节数。 */
  public record SkillFileEntry(String path, long size) {}

  /** 目录展示用的包摘要：只有包名、描述与技能清单，不含任何凭据。 */
  public record PackageSummary(
      String packageName, String description, List<SkillManifestEntry> skills) {}

  /**
   * 读取指定 Skill Package 下某个 Skill 的文件字节。
   *
   * @param packageName Package 名称，非空
   * @param skillName Skill 名称，非空
   * @param relativePath 相对文件路径，非空
   * @return 文件的原始字节
   * @throws PlatformReadException Package 不存在、Skill 不存在、路径非法或归档读取失败时抛出
   */
  public byte[] readSkillFile(String packageName, String skillName, String relativePath) {
    if (packageName == null || packageName.isBlank()) {
      throw new PlatformReadException("packageName must not be blank");
    }
    if (skillName == null || skillName.isBlank()) {
      throw new PlatformReadException("skillName must not be blank");
    }
    validateRelativePath(relativePath);

    SkillPackage pkg = skillPackageRepository.getPackage(packageName);
    if (pkg == null) {
      throw new PlatformReadException("skill package not found: " + packageName);
    }

    SkillManifestEntry skill = pkg.findSkill(skillName);
    if (skill == null) {
      throw new PlatformReadException("skill not found: " + packageName + "/" + skillName);
    }

    try {
      return archiveFileReader.readFile(pkg.artifactBlobId(), skillName + "/" + relativePath);
    } catch (RuntimeException e) {
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
