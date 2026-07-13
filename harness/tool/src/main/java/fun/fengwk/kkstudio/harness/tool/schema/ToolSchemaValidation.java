package fun.fengwk.kkstudio.harness.tool.schema;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ToolSchemaValidation {

  private ToolSchemaValidation() {}

  static Map<String, ToolSchemaElement> copyProperties(Map<String, ToolSchemaElement> properties) {
    Map<String, ToolSchemaElement> copied =
        Map.copyOf(Objects.requireNonNull(properties, "properties"));
    if (copied.keySet().stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new IllegalArgumentException("properties must use non-blank names");
    }
    return copied;
  }

  static Set<String> copyRequired(Set<String> required, Map<String, ToolSchemaElement> properties) {
    Set<String> copied = Set.copyOf(Objects.requireNonNull(required, "required"));
    if (!properties.keySet().containsAll(copied)) {
      throw new IllegalArgumentException("required properties must be declared");
    }
    return copied;
  }
}
