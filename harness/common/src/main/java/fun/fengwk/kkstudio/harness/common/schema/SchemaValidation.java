package fun.fengwk.kkstudio.harness.common.schema;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class SchemaValidation {

  private SchemaValidation() {}

  static Map<String, SchemaElement> copyProperties(Map<String, SchemaElement> properties) {
    Map<String, SchemaElement> copied =
        Map.copyOf(Objects.requireNonNull(properties, "properties"));
    if (copied.keySet().stream().anyMatch(name -> name == null || name.isBlank())) {
      throw new IllegalArgumentException("properties must use non-blank names");
    }
    return copied;
  }

  static Set<String> copyRequired(Set<String> required, Map<String, SchemaElement> properties) {
    Set<String> copied = Set.copyOf(Objects.requireNonNull(required, "required"));
    if (!properties.keySet().containsAll(copied)) {
      throw new IllegalArgumentException("required properties must be declared");
    }
    return copied;
  }
}
