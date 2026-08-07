package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

/** live Environment 发布的紧凑技能能力。 */
@Data
public class LiveEnvironmentSkillDTO {
  /** 技能名（daemon 声明）。 */
  private String name;

  /** 技能描述。 */
  private String description;
}
