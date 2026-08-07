package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author fengwk
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefinitionCreateDTO extends AgentDefinitionEditablePropertiesDTO {

  /** 必填 Agent 唯一名（资源路由身份）：trim 后非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 必填模型引用，序列化形式为 {@code providerName/modelName}；provider 与 model 必须已存在。 */
  private String model;
}
