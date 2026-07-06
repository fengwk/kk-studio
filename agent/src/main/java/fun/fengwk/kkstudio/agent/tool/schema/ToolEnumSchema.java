package fun.fengwk.kkstudio.agent.tool.schema;

import java.util.List;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * ToolEnumSchema 表示枚举字符串参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolEnumSchema implements ToolSchemaElement {

  /** 参数描述。 */
  private final String description;

  /** 允许的枚举值列表。 */
  private final List<String> enumValues;
}
