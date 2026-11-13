package fun.fengwk.kkstudio.harness.api.history;

import java.util.Objects;

/** A provider-hosted tool definition preserved verbatim as a JSON object in SYSTEM history. */
public record SystemHostedTool(String name, String definitionJson) {

  public SystemHostedTool {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(definitionJson, "definitionJson");
    if (name.isBlank() || !name.equals(name.strip())) {
      throw new IllegalArgumentException("name must be non-blank and canonical");
    }
    if (definitionJson.isBlank()) {
      throw new IllegalArgumentException("definitionJson must not be blank");
    }
  }
}
