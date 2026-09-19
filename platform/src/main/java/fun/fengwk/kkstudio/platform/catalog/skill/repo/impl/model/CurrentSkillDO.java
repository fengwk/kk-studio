package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

/** {@code skill} 目录行与不可变 {@code skill_revision} 的联合投影。 */
@Data
public class CurrentSkillDO {

  private String name;
  private String packageName;
  private String packageVersion;
  private String description;
  private String contentRevision;
  private String content;
}
