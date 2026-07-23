package fun.fengwk.kkstudio.harness.runtime.configuration;

import java.util.Objects;

/** 冻结的 Skill 摘要快照（name / description / source environment）；三个字段均非空白。 */
public record SkillSnapshot(String name, String description, String sourceEnvironment) {

  public SkillSnapshot {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    sourceEnvironment = requireNonBlank(sourceEnvironment, "sourceEnvironment");
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
