package fun.fengwk.kkstudio.core.ai.runtime.execution;

import java.util.Set;

/** 当前节点可接收 Environment Tool 的 Environment 名称快照。 */
@FunctionalInterface
public interface ExecutionActivationEnvironmentEligibility {

  /** 返回当前 READY 的 Environment 名称集合。 */
  Set<String> readyEnvironmentNames();

  /** 不接收任何 Environment Tool 的空快照。 */
  static ExecutionActivationEnvironmentEligibility empty() {
    return Set::of;
  }

  /** 从名称集合创建不可变快照适配器。 */
  static ExecutionActivationEnvironmentEligibility of(Set<String> names) {
    return () -> names == null ? Set.of() : Set.copyOf(names);
  }
}
