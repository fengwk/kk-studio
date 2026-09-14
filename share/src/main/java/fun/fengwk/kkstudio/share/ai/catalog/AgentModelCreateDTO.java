package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {

  /** 必填所属 Provider 名（与 name 共同构成资源身份）：trim 后非空白、≤64 字符。 */
  private String providerName;
}
