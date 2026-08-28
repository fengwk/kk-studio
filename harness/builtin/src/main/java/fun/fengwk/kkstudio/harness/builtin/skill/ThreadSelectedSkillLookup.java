package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 为 HOST tool 解析当前 Thread Agent 已选中的 skill 元数据。
 *
 * <p>以 durable Model invocation binding 为第一事实；只允许加载声明已选中的 skill，未选中则拒绝加载。
 */
@FunctionalInterface
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
