package fun.fengwk.kkstudio.harness.tool.schema;

import java.util.List;

/** 字符串枚举 schema。 */
public record ToolEnumSchema(String description, List<String> values) implements ToolSchemaElement {

  public ToolEnumSchema {
    values = List.copyOf(values);
    if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("values must contain non-blank entries");
    }
  }
}
