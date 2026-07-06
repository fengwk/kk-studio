package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

/**
 * ToolArraySchema 表示数组类型参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolArraySchema implements ToolSchemaElement {

  /** 参数描述。 */
  private final String description;

  /** 数组元素 schema。 */
  private final ToolSchemaElement items;
}
