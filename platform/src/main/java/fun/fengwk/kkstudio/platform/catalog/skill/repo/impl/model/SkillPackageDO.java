package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code skill_package} 行映射：不可变的 package 版本。 */
@Data
public class SkillPackageDO {

  private String packageName;
  private String packageVersion;
  private String description;
  private String packageRevision;
  private Boolean active;
  private Instant createTime;
}
