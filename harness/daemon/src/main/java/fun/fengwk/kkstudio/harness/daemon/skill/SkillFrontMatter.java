package fun.fengwk.kkstudio.harness.daemon.skill;

/**
 * SKILL.md YAML front matter 中用于发现的最小字段。
 *
 * <p>只解析 {@code name} 与 {@code description}；其它键（如 allowed-tools）忽略。
 */
public record SkillFrontMatter(String name, String description) {

  public SkillFrontMatter {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
