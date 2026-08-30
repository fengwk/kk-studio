package fun.fengwk.kkstudio.harness.common.schema;

import java.util.List;

/** 字符串枚举 schema。 */
public record EnumSchema(String description, List<String> values) implements SchemaElement {

  public EnumSchema {
    values = List.copyOf(values);
    if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("values must contain non-blank entries");
    }
  }
}
