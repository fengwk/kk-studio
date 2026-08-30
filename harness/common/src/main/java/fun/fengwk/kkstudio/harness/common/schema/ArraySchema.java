package fun.fengwk.kkstudio.harness.common.schema;

import java.util.Objects;

/** 数组 schema。 */
public record ArraySchema(String description, SchemaElement items) implements SchemaElement {

  public ArraySchema {
    items = Objects.requireNonNull(items, "items");
  }
}
