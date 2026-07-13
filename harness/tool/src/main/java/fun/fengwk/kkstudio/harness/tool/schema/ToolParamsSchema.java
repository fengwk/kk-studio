package fun.fengwk.kkstudio.harness.tool.schema;

import java.util.Map;
import java.util.Set;

/** 工具顶层参数对象 schema。 */
public record ToolParamsSchema(
    String description,
    Map<String, ToolSchemaElement> properties,
    Set<String> required,
    boolean additionalProperties) {

  public ToolParamsSchema {
    properties = ToolSchemaValidation.copyProperties(properties);
    required = ToolSchemaValidation.copyRequired(required, properties);
  }
}
