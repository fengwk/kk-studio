package fun.fengwk.kkstudio.harness.runtime.skill;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 为 PLATFORM tool 解析当前 Thread Agent 已选中的 skill 元数据。
 *
 * <p>实现位于 Core，以避免 platform Tool bean 与 Host 端 Environment gateway 组件之间的 Spring 循环装配。
 */
public interface ThreadSelectedSkillLookup {

  /** 随已 claim 的 Model invocation 持久化的 skill binding。 */
  List<SkillBinding> selectedSkills(UUID invocationId, UUID threadId);

  default Optional<SkillBinding> findSelected(UUID invocationId, UUID threadId, String skillName) {
    if (skillName == null || skillName.isBlank()) {
      return Optional.empty();
    }
    for (SkillBinding skill : selectedSkills(invocationId, threadId)) {
      if (skill.name().equals(skillName)) {
        return Optional.of(skill);
      }
    }
    return Optional.empty();
  }
}
