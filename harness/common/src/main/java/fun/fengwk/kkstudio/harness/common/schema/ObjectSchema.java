package fun.fengwk.kkstudio.harness.common.schema;

import java.util.Map;
import java.util.Set;

/** 嵌套对象 schema。 */
public record ObjectSchema(
    String description,
    Map<String, SchemaElement> properties,
    Set<String> required,
    boolean additionalProperties)
    implements SchemaElement {

  public ObjectSchema {
    properties = SchemaValidation.copyProperties(properties);
    required = SchemaValidation.copyRequired(required, properties);
  }
}
