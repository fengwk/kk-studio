package fun.fengwk.kkstudio.harness.tool.schema;

import java.util.Map;
import java.util.Set;

/** 嵌套对象 schema。 */
public record ToolObjectSchema(
    String description,
    Map<String, ToolSchemaElement> properties,
    Set<String> required,
    boolean additionalProperties)
    implements ToolSchemaElement {

  public ToolObjectSchema {
    properties = ToolSchemaValidation.copyProperties(properties);
    required = ToolSchemaValidation.copyRequired(required, properties);
  }
}
