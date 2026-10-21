package fun.fengwk.kkstudio.platform.catalog.skill;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * {@code skill_package} 行：一个 Git Skill Package 的权威事实。
 *
 * <p>每个 {@code packageName} 恰一行：{@code repositoryUrl} 一经创建不可修改，{@code branch} 只用于检查候选更新，{@code
 * currentCommit}（40 或 64 位小写 hex）与按 name 排序的 {@code skills} manifest 是同一次 CAS
 * 发布的原子组合。检查只更新观察三元组（{@code observedHeadCommit} / {@code headCheckedAt} / {@code
 * headCheckError}），事实未变化的写请求不推进 {@code version}。
 *
 * <p>{@code token} 是私有仓库访问令牌，null 表示匿名访问；它绝不回显、绝不进入日志或模型上下文，因此 {@link #toString()} 不输出它。
 */
@Builder(toBuilder = true)
public record SkillPackage(
    String packageName,
    String description,
    String repositoryUrl,
    String branch,
    String currentCommit,
    String observedHeadCommit,
    Instant headCheckedAt,
    String headCheckError,
    List<SkillManifestEntry> skills,
    String token,
    long version,
    Instant createTime,
    Instant updateTime) {

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

  @Override
  public String toString() {
    return "SkillPackage[packageName="
        + packageName
        + ", repositoryUrl="
        + repositoryUrl
        + ", branch="
        + branch
        + ", currentCommit="
        + currentCommit
        + ", observedHeadCommit="
        + observedHeadCommit
        + ", skills="
        + skills
        + ", version="
        + version
        + "]";
  }
}
