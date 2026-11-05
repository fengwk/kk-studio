package fun.fengwk.kkstudio.platform.catalog.skill;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code skill_package} 行：一个 Skill Package 当前版本的权威事实。
 *
 * <p>技能包是「名称 + 当前版本」：{@code packageVersion}、{@code artifactBlobId}、{@code skills} 与 {@code
 * publishedAt} 是同一次 CAS 发布的原子组合，发布切换后不保留任何历史版本。{@code artifactBlobId} 指向服务端生成的规范化 ZIP 归档，归档的条目索引保存在
 * {@code storage_blob.archive_index}。
 *
 * <p>{@code gitSource} 只有 Git 渠道的包才非 null；上传渠道的包没有这一行。
 */
public record SkillPackage(
    String packageName,
    String description,
    String packageVersion,
    UUID artifactBlobId,
    List<SkillManifestEntry> skills,
    Instant publishedAt,
    long version,
    Instant createdAt,
    Instant updatedAt,
    SkillPackageGitSource gitSource) {

  /** 返回 manifest 中指定名称的元素；不存在返回 null。 */
  public SkillManifestEntry findSkill(String name) {
    if (skills == null || name == null) {
      return null;
    }
    for (SkillManifestEntry entry : skills) {
      if (entry.name().equals(name)) {
        return entry;
      }
    }
    return null;
  }

  /** 是否为 Git 渠道的包。 */
  public boolean isGit() {
    return gitSource != null;
  }

  @Override
  public String toString() {
    return "SkillPackage[packageName="
        + packageName
        + ", packageVersion="
        + packageVersion
        + ", artifactBlobId="
        + artifactBlobId
        + ", skills="
        + skills
        + ", publishedAt="
        + publishedAt
        + ", version="
        + version
        + ", gitSource="
        + gitSource
        + "]";
  }
}
