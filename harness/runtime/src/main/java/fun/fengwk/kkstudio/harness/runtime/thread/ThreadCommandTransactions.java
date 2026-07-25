package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Thread command 用例的原子持久化端口。
 *
 * <p>实现必须使用 final {@code harness_*} schema，并只把 {@link ExecutionTarget} 当作提交后的 best-effort
 * activation 信号。Redis/dispatcher 失败绝不回滚已提交的 durable mutation。
 */
public interface ThreadCommandTransactions {

  /**
   * 原子创建 Session、ROOT Entry、初始 {@code RUNTIME_CONFIG} Entry 与 Main Thread。
   *
   * <p>{@code initialConfig} 必须是调用方在事务外已冻结的完整快照；本方法只持久化该快照。
   */
  SessionCreation createSession(String title, RuntimeConfigSnapshot initialConfig, Instant now);

  /** 从同一 Session 的指定 Entry 创建 branch Thread。 */
  HarnessThread createBranch(long sessionId, long fromEntryId, Instant now);

  /**
   * 锁定 Thread 并以单条 SQL 优先读取先前 QUEUED config Input，否则读取 head path 最近 RUNTIME_CONFIG。
   *
   * <p>调用方必须在同一个外层命令事务内随后完成 {@link #enqueue}，以便 SET_MODEL/SET_YOLO/SET_AGENT 的 snapshot 选择和
   * sequence 分配串行化。
   */
  Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(long threadId);

  /**
   * 按稳定 idempotency key 查找已持久化 Input。
   *
   * <p>command facade 必须在解析任何 live Definition/Model 之前调用此方法；命中时直接返回，不得重新校验已变化的 live resource。返回
   * target 仍须在外层事务提交后 best-effort notify。
   */
  Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey);

  /** 锁定 Thread、分配 sequence、幂等写入 input 并使 Thread runnable。 */
  EnqueueResult enqueue(
      long threadId, ThreadInputPayload payload, String idempotencyKey, Instant now);

  /** epoch fencing stop：取消未开始的 input/invocation，RUNNING 只由 epoch 失效。 */
  StopResult stop(long threadId, Instant now);

  record SessionCreation(Session session, SessionEntry rootEntry, HarnessThread mainThread) {}

  record EnqueueResult(ThreadInput input, ExecutionTarget target) {}

  record StopResult(
      long executionEpoch, List<ThreadInput> cancelledInputs, ExecutionTarget target) {}
}
