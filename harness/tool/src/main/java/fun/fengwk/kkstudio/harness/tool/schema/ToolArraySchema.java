package fun.fengwk.kkstudio.harness.tool.schema;

import java.util.Objects;

/** 数组 schema。 */
public record ToolArraySchema(String description, ToolSchemaElement items)
    implements ToolSchemaElement {

  public ToolArraySchema {
    items = Objects.requireNonNull(items, "items");
  }
}
