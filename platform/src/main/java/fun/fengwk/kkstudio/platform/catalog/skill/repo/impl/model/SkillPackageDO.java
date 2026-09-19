package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code skill_package} 行映射：一个 package 版本。 */
@Data
public class SkillPackageDO {

  private String packageName;
  private String packageVersion;
  private String description;
  private Boolean active;
  private Instant createTime;
}
