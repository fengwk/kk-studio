package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentProviderCreateDTO extends AgentProviderEditablePropertiesDTO {

  /** 必填 Provider 唯一名（资源路由身份）：trim 后非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;
}
