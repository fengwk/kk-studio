package fun.fengwk.kkstudio.platform.catalog.skill;

import fun.fengwk.kkstudio.platform.catalog.dto.SkillGitSourceDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillManifestEntryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageFileDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.util.Comparator;
import java.util.List;

/**
 * 只读投影：把发布模型与归档 blob 映射成 HTTP DTO。
 *
 * <p>摘要不含归档摘要与 Git 观察值，详情才带 Git 源与归档尺寸；两者都不承载 Skill 正文。
 */
final class SkillPackageProjection {

  private SkillPackageProjection() {}

  static List<SkillPackageSummaryDTO> toSummaries(List<SkillPackage> packages) {
    return packages.stream().map(SkillPackageProjection::toSummary).toList();
  }

  static SkillPackageSummaryDTO toSummary(SkillPackage skillPackage) {
    SkillPackageSummaryDTO dto = new SkillPackageSummaryDTO();
    dto.setPackageName(skillPackage.packageName());
    dto.setDescription(skillPackage.description());
    dto.setPackageVersion(skillPackage.packageVersion());
    dto.setPublishedAt(skillPackage.publishedAt());
    dto.setSkills(skills(skillPackage));
    dto.setSource(sourceOf(skillPackage));
    dto.setUpdateAvailable(updateAvailable(skillPackage));
    dto.setVersion(skillPackage.version());
    dto.setCreatedAt(skillPackage.createdAt());
    dto.setUpdatedAt(skillPackage.updatedAt());
    return dto;
  }

  static SkillPackageDetailDTO toDetail(SkillPackage skillPackage, StorageBlob artifact) {
    SkillPackageDetailDTO dto = new SkillPackageDetailDTO();
    dto.setPackageName(skillPackage.packageName());
    dto.setDescription(skillPackage.description());
    dto.setPackageVersion(skillPackage.packageVersion());
    dto.setPublishedAt(skillPackage.publishedAt());
    dto.setSkills(skills(skillPackage));
    dto.setSource(sourceOf(skillPackage));
    if (skillPackage.isGit()) {
      SkillGitSourceDTO git = new SkillGitSourceDTO();
      git.setRepositoryUrl(skillPackage.gitSource().repositoryUrl());
      git.setBranch(skillPackage.gitSource().branch());
      git.setToken(SecretConfiguredDTO.of(skillPackage.gitSource().token() != null));
      dto.setGit(git);
      dto.setHeadCommit(skillPackage.gitSource().headCommit());
      dto.setHeadCheckedAt(skillPackage.gitSource().checkedAt());
      dto.setHeadCheckError(skillPackage.gitSource().checkError());
    }
    dto.setUpdateAvailable(updateAvailable(skillPackage));
    dto.setArtifactSha256(artifact.getSha256());
    dto.setArtifactSizeBytes(artifact.getSizeBytes());
    dto.setVersion(skillPackage.version());
    dto.setCreatedAt(skillPackage.createdAt());
    dto.setUpdatedAt(skillPackage.updatedAt());
    return dto;
  }

  /** 文件清单按路径确定性排序；条目只来自服务端生成的归档索引。 */
  static List<SkillPackageFileDTO> toFiles(SkillArchiveIndex index) {
    return index.entries().stream()
        .sorted(Comparator.comparing(SkillArchiveIndex.Entry::path))
        .map(SkillPackageProjection::toFile)
        .toList();
  }

  static List<SkillManifestEntryDTO> skills(SkillPackage skillPackage) {
    return skillPackage.skills() == null
        ? List.of()
        : skillPackage.skills().stream().map(SkillPackageProjection::toSkill).toList();
  }

  static List<SkillManifestEntryDTO> toSkills(List<SkillManifestEntry> skills) {
    return skills.stream().map(SkillPackageProjection::toSkill).toList();
  }

  /** Git 渠道检查到的 HEAD 与当前版本不同即视为有更新可发布。 */
  private static boolean updateAvailable(SkillPackage skillPackage) {
    return skillPackage.isGit()
        && skillPackage.gitSource().headCommit() != null
        && !skillPackage.gitSource().headCommit().equals(skillPackage.packageVersion());
  }

  private static SkillPackageSource sourceOf(SkillPackage skillPackage) {
    return skillPackage.isGit() ? SkillPackageSource.GIT : SkillPackageSource.UPLOAD;
  }

  private static SkillPackageFileDTO toFile(SkillArchiveIndex.Entry entry) {
    SkillPackageFileDTO dto = new SkillPackageFileDTO();
    dto.setPath(entry.path());
    dto.setSize(entry.size());
    return dto;
  }

  private static SkillManifestEntryDTO toSkill(SkillManifestEntry entry) {
    SkillManifestEntryDTO dto = new SkillManifestEntryDTO();
    dto.setName(entry.name());
    dto.setDescription(entry.description());
    return dto;
  }
}
