package fun.fengwk.kkstudio.harness.runtime.goal;

import java.time.Instant;
import java.util.Optional;

/** 当前 Thread 作用域 goal 的 durable store。 */
public interface GoalStore {

  Optional<ThreadGoal> find(long threadId);

  /** 以 {@link GoalStatus#active} 创建或替换当前 Thread goal。 */
  ThreadGoal createOrReplace(long threadId, String objective, Long tokenBudget, Instant now);

  /**
   * 对 active goal 进行 terminal update。
   *
   * @throws IllegalStateException 当不存在 active goal 时
   * @throws IllegalArgumentException 当 status/reason 非法时
   */
  ThreadGoal updateTerminal(long threadId, GoalStatus status, String reason, Instant now);
}
