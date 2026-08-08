package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.util.Objects;

/** 本地已发现的 skill：capabilities 摘要 + skill 指令正文（SKILL.md 去除 front matter）。 */
public record DaemonSkill(String name, String description, String body) {

  public DaemonSkill {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    body = Objects.requireNonNull(body, "body");
  }

  public DaemonSkillDescriptor descriptor() {
    return new DaemonSkillDescriptor(name, description);
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
