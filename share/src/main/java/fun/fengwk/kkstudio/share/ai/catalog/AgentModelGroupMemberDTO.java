package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 模型组的一项有序成员引用：逻辑模型名 + 该单模型内具体 Variant。 */
@Data
public class AgentModelGroupMemberDTO {

  /** 必填单 Model 的全局逻辑名称；不得引用 GROUP。 */
  private String modelName;

  /** 必填该 Model config 中声明的 Variant id；模型组没有组级 Variant。 */
  private String variant;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown model group member field: " + name);
  }
}
