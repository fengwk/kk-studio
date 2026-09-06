package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code /api/ai/catalog/models/{providerName}/{modelName}} 的 PUT 请求体；{@link #expectedVersion} 必填。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {

  /** 必填非负十进制字符串；必须与当前模型版本一致。 */
  private String expectedVersion;
}
