package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code POST /api/platform/agents} 的请求体。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentCreateDTO extends AgentEditablePropertiesDTO {

  /** 必填 Agent 唯一名（资源路由身份）：trim 后非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;
}
