package fun.fengwk.kkstudio.harness.common.schema;

import java.util.Map;
import java.util.Set;

/** 顶层输入参数对象 schema。 */
public record InputSchema(
    String description,
    Map<String, SchemaElement> properties,
    Set<String> required,
    boolean additionalProperties) {

  public InputSchema {
    properties = SchemaValidation.copyProperties(properties);
    required = SchemaValidation.copyRequired(required, properties);
  }
}
