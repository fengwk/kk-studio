package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;

/** 模型组的一项有序成员引用：逻辑模型名 + 该单模型内具体 Variant。 */
@Data
public class ModelGroupMemberDTO {

  /** 必填单 Model 的全局逻辑名称；不得引用 GROUP。 */
  private String modelName;

  /** 必填该 Model config 中声明的 Variant id；模型组没有组级 Variant。 */
  private String variant;
}
