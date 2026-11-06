package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code PUT /api/platform/agents/{name}} 的请求体；{@link #expectedVersion} 必填。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentUpdateDTO extends AgentEditablePropertiesDTO {

  /** 必填非负十进制字符串；必须与当前 Agent 版本一致。 */
  private String expectedVersion;
}
