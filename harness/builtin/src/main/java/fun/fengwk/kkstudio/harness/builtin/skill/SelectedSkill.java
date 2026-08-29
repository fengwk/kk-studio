package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;

/**
 * 选中的 Skill 描述元数据。
 *
 * @param name skill 标识名，非空
 * @param description 描述，可为 null
 * @param sourceEnvironment skill 所在的源环境绑定，可为 null
 */
public record SelectedSkill(String name, String description, EnvironmentBinding sourceEnvironment) {

  public SelectedSkill {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    name = name.strip();
  }
}
