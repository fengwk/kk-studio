package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 选中的 Skill 描述元数据。
 *
 * @param name skill 标识名，非空
 * @param description 描述，可为 null
 * @param sourceEnvironmentId skill 所在的源环境身份，可为 null
 */
public record SelectedSkill(String name, String description, EnvironmentId sourceEnvironmentId) {

  public SelectedSkill {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    name = name.strip();
  }
}
