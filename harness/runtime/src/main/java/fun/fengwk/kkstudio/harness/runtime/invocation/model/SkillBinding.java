package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/**
 * Immutable skill fact frozen into one Model invocation.
 *
 * <p>The body is intentionally absent: only the stable canonical name and description, plus the
 * nullable source Environment route, are durable request facts. Display metadata and skill body
 * never enter the request.
 */
public record SkillBinding(String name, String description, EnvironmentId sourceEnvironmentId) {

  public SkillBinding {
    name = requireCanonical(name, "name", 128);
    description = requireCanonical(description, "description", 1024);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
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
