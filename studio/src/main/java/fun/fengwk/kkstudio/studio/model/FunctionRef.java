package fun.fengwk.kkstudio.studio.model;

import java.util.Objects;

/** Immutable Function identity + version pointer. */
public record FunctionRef(String functionId, String version) {

  public FunctionRef {
    Objects.requireNonNull(functionId, "functionId");
    Objects.requireNonNull(version, "version");
    if (functionId.isBlank()) {
      throw new IllegalArgumentException("functionId must not be blank");
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
  }
}
