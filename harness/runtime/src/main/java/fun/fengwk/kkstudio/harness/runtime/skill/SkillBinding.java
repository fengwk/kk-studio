package fun.fengwk.kkstudio.harness.runtime.skill;

import java.util.Objects;

/**
 * Immutable skill fact frozen into one model invocation.
 *
 * <p>The body is intentionally absent. The name, description, and source environment are the exact
 * metadata used to build the provider prompt and to route a later {@code load_skill} call.
 */
public record SkillBinding(String name, String description, String sourceEnvironment) {

  public SkillBinding {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    sourceEnvironment = requireNonBlank(sourceEnvironment, "sourceEnvironment");
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    return value;
  }
}
