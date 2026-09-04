package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import java.util.Objects;

/** 一次 Model Invocation 可委派的 Subagent 名称与展示描述快照。 */
public record SubagentBinding(String name, String description) {

  public SubagentBinding {
    name = requireCanonical(name, "name", 64);
    description = Objects.requireNonNull(description, "description");
    if (description.length() > 512) {
      throw new IllegalArgumentException("description must be <= 512 characters");
    }
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be <= " + maxLength + " characters");
    }
    return value;
  }
}
