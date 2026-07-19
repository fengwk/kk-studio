package fun.fengwk.kkstudio.harness.tool.daemon;

/**
 * Daemon CAPABILITIES 中暴露的 Skill 摘要。
 *
 * <p>仅包含可安全上报的短字段；本地路径与完整 SKILL.md 正文不进入 capabilities wire。
 */
public record DaemonSkillDescriptor(String name, String description) {

  public DaemonSkillDescriptor {
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
