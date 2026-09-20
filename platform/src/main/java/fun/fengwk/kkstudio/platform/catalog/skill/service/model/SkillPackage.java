package fun.fengwk.kkstudio.platform.catalog.skill.service.model;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * {@code skill_package} 行的领域模型：一个 package 的权威事实。
 *
 * <p>每个 {@code packageName} 恰一行：{@code repositoryUrl} 一经创建不可修改，{@code branch} 只用于检查候选更新， {@code
 * currentCommit} 与 {@code skills} 是同一次 CAS 发布的原子组合。检查只更新观察三元组 （{@code observedHeadCommit}/{@code
 * headCheckedAt}/{@code headCheckError}），事实未变化的写请求不推进 {@code version}。
 */
@Data
public class SkillPackage {

  /** package 名（主键与不可变路由身份）。 */
  private String packageName;

  /** 可空 package 描述；null 表示未填写。 */
  private String description;

  /** 不可变 Git repository URL。 */
  private String repositoryUrl;

  /** 只用于检查候选更新的 branch；不决定已发布内容。 */
  private String branch;

  /** 人工确认并已发布的 exact Git object id（40 或 64 位小写 hex）。 */
  private String currentCommit;

  /** 最近一次成功检查到的 branch HEAD；从未成功检查时为 null。 */
  private String observedHeadCommit;

  /** 最近一次检查时间；从未检查时为 null。 */
  private Instant headCheckedAt;

  /** 最近一次检查的有界错误摘要；检查成功时为 null。 */
  private String headCheckError;

  /** 从 {@code currentCommit} 派生的 Skill manifest，按 name 排序。 */
  private List<SkillManifestEntry> skills;

  /** CAS 乐观锁版本：非负，实际事实变化时 +1。 */
  private long version;

  /** 创建时间（毫秒精度）。 */
  private Instant createTime;

  /** 最后更新时间（毫秒精度）。 */
  private Instant updateTime;

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
}
