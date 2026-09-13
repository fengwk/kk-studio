package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 单个有界扫描诊断响应 DTO：只描述“哪个位置发生了什么”，绝不复述配置原值或 SKILL.md 内容。 */
@Data
public class EnvironmentSkillDiagnosticDTO {

  /** 诊断位置（宿主上的目录或文件展示文本）。 */
  private String location;

  /** 诊断说明（有界文本）。 */
  private String message;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment skill diagnostic field: " + fieldName);
  }
}
