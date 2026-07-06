package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

import lombok.Builder;
import lombok.Data;

/**
 * ToolBooleanSchema 表示布尔类型参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolBooleanSchema implements ToolSchemaElement {

  /** 参数描述。 */
  private final String description;
}
