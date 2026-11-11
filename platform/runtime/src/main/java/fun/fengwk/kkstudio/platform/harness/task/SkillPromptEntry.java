package fun.fengwk.kkstudio.platform.harness.task;

import java.util.Objects;

/** 仅供 Prompt 渲染的 Skill 名称与描述。 */
public record SkillPromptEntry(String name, String description) {

  public SkillPromptEntry {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
  }
}
