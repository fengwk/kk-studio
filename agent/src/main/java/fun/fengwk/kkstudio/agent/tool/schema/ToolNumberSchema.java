package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

import lombok.Builder;
import lombok.Data;

/**
 * ToolNumberSchema 表示浮点数类型参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolNumberSchema implements ToolSchemaElement {

  /** 参数描述。 */
  private final String description;
}
