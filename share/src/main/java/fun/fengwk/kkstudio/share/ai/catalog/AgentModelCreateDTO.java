package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {

  /** 必填模型唯一名（与 providerName 共同构成资源身份）：trim 后非空白、≤128 字符。 */
  private String providerName;

  /** 必填模型逻辑名：trim 后非空白、≤128 字符。 */
  private String name;
}
