package fun.fengwk.kkstudio.harness.runtime.context;

import java.util.Objects;

/**
 * Resolved metadata for one selected Agent skill used in Turn runtime config and system prompt.
 *
 * <p>Only short name, description, and source Environment name are retained. Skill body and local
 * paths never enter runtime config or Entry Tree.
 */
public record SelectedSkillMetadata(String name, String description, String sourceEnvironment) {
  public SelectedSkillMetadata {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    sourceEnvironment = requireNonBlank(sourceEnvironment, "sourceEnvironment");
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
