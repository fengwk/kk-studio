package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

/**
 * ToolIntegerSchema 表示整数类型参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolIntegerSchema implements ToolSchemaElement {

  /** 参数描述。 */
  private final String description;
}
