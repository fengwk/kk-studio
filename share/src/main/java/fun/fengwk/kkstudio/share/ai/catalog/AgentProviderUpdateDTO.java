package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code /api/ai/catalog/providers/{name}} 的 PUT 请求体；{@link #expectedVersion} 必填。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentProviderUpdateDTO extends AgentProviderEditablePropertiesDTO {

  /** 必填非负十进制字符串；必须与当前 Provider 版本一致。 */
  private String expectedVersion;
}
