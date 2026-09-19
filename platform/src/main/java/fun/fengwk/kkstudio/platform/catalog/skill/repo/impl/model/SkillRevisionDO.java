package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code skill_revision} 行映射：不可变的 Skill 内容版本。 */
@Data
public class SkillRevisionDO {

  private String packageName;
  private String packageVersion;
  private String name;
  private String description;
  private String content;
  private String contentRevision;
  private Instant createTime;
}
