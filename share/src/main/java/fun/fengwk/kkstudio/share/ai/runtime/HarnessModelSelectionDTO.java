package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 冻结进 branch settings 快照的统一模型选择：全局逻辑名称 + 可选单模型 Variant。 */
@Data
public class HarnessModelSelectionDTO {

  /** 必填全局逻辑模型名（MODEL 或 GROUP，canonical text，≤128 字符）。 */
  private String modelName;

  /** 可空 Variant id；单 MODEL 可覆盖，GROUP 必须为 null（成员 Variant 在组内配置）。 */
  private String variant;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown model selection field: " + name);
  }
}
