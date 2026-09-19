package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code skill} 行映射：一个 Skill 的精确内容事实。 */
@Data
public class SkillDO {

  private String packageName;
  private String packageVersion;
  private String name;
  private String description;
  private String content;
  private Boolean active;
  private Instant createTime;
}
