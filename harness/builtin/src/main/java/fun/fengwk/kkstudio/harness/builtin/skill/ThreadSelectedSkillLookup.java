package fun.fengwk.kkstudio.harness.builtin.skill;

import java.util.Optional;
import java.util.UUID;

/**
 * 解析当前 Thread Agent 已选中的 skill 元数据。
 *
 * <p>以 durable Model invocation binding 为第一事实；只允许加载声明已选中的 skill，未选中则拒绝加载。
 */
@FunctionalInterface
public interface ThreadSelectedSkillLookup {

  /** 查找指定 invocation/thread 中已选中的 skill。 */
  Optional<SelectedSkill> findSelected(UUID invocationId, UUID threadId, String skillName);
}
