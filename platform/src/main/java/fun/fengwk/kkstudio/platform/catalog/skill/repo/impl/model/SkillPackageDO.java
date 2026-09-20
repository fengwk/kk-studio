package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code skill_package} 行映射：一个 package 的权威事实（Skill 正文与目录树只存在于 Git）。 */
@Data
public class SkillPackageDO {

  private String packageName;
  private String description;
  private String repositoryUrl;
  private String branch;
  private String currentCommit;
  private String observedHeadCommit;
  private Instant headCheckedAt;
  private String headCheckError;

  /** {@code skills} jsonb 列的文本形式（JSON array，元素为 {@code {name, description}}）。 */
  private String skillsJson;

  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
