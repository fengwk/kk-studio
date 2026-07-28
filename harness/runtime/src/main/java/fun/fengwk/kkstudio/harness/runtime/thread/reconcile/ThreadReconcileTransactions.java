package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;

import java.time.Instant;
import java.util.Optional;

/**
 * Thread reconcile 的用例事务端口。
 *
 * <p>每个方法对应一种原子用例；禁止跨方法拼事务。所有 mutation 携带 {@link ThreadOwnership} 作为 fencing identity。本端口不暴露
 * Repository、Mapper 或 Spring transaction 类型。各方法只返回自身语义需要的有限结果：
 *
 * <ul>
 *   <li>apply Model / apply Tool / harvest 共享 {@link ApplyOutcome}；
 *   <li>suspendAndRecheck 返回 {@link SuspendOutcome}；
 *   <li>quiesceAndRecheck 返回 {@link QuiesceOutcome}；
 *   <li>createModelInvocationAndRelease 返回 {@link ModelCreationOutcome}（{@link
 *       ModelCreationOutcome.Created} | {@link ModelCreationOutcome.LostOwnership} | {@link
 *       ModelCreationOutcome.Failed}）。
 * </ul>
 *
 * <p>意外 RuntimeException 必须由适配器向上抛出；Reconciler 捕获后 best-effort release 并重新抛出。
 */
public interface ThreadReconcileTransactions {

  /**
   * 尝试获得 Thread reconcile ownership。
   *
   * @return 成功时返回带 {@code executionEpoch} 与 {@code processorToken} 的 ownership；Thread 不可运行或 lease
   *     已被其他持有者占用时返回 empty
   */
  Optional<ThreadOwnership> claim(long threadId, String processorToken, Instant now);

  /** 在持有 ownership 时续租 lease；fencing 失败返回 false。 */
  boolean renew(ThreadOwnership ownership, Instant now);

  /**
   * 在持有 ownership 时读取一致的 reconcile snapshot，包含 terminal-unapplied ModelInvocation、ready-to-apply
   * Tool 批次、durable blocker、queued Inputs 与 ModelInvocationPlan。
   *
   * <p>fencing 失败或 Thread 已不存在时返回 empty。
   */
  Optional<ThreadReconcileSnapshot> loadOwnedSnapshot(ThreadOwnership ownership, Instant now);

  /**
   * 应用已 terminal 且尚未 applied 的 ModelInvocation；写入 Assistant/AssistantError Entry、Usage、可选
   * ToolInvocations、head 推进与 {@code appliedAt}。意外错误以 RuntimeException 抛出。
   */
  ApplyOutcome applyTerminalModel(ThreadOwnership ownership, long modelInvocationId, Instant now);

  /**
   * 应用当前 Assistant 的全部 terminal Tool sibling；按 ordinal 写 Tool Result Entry 并设置所有 sibling {@code
   * appliedAt}。意外错误以 RuntimeException 抛出。
   */
  ApplyOutcome applyTerminalToolResults(
      ThreadOwnership ownership, long assistantEntryId, Instant now);

  /**
   * 原子校验 {@code expectedBlocker} 仍是当前 durable blocker 并释放 Thread lease；若 sibling 已终态、blocker 已替换或新
   * work 到达则返回 {@link SuspendOutcome#WORK_AVAILABLE}，调用方仍持有 lease。返回 {@link
   * SuspendOutcome#SUSPENDED} 时，Reconciler 使用调用时的 {@code expectedBlocker} 构造结果，事务没有替换 blocker
   * 的通道。意外错误以 RuntimeException 抛出。
   */
  SuspendOutcome suspendAndRecheck(
      ThreadOwnership ownership, ContinuationRef expectedBlocker, Instant now);

  /**
   * 原子 harvest 一个 {@link TurnBoundary}：连续配置 Input 先于末端 message 应用；CAS {@code APPLIED} 并按需推进
   * head。意外错误以 RuntimeException 抛出。
   */
  ApplyOutcome harvestBoundary(ThreadOwnership ownership, TurnBoundary boundary, Instant now);

  /**
   * 原子创建 ModelInvocation 并在同事务内释放 Thread lease。plan 的 {@code sourceHeadEntryId} 必须等于 ownership 对应
   * Thread 的当前 head。仅此处保留 typed failure 通道；意外错误以 RuntimeException 抛出。
   */
  ModelCreationOutcome createModelInvocationAndRelease(
      ThreadOwnership ownership, ModelInvocationPlan plan, Instant now);

  /**
   * 原子确认 Thread 无可推进 work 并释放 lease；若 recheck 发现新 work 则返回 {@link
   * QuiesceOutcome#WORK_AVAILABLE}，调用方仍持有 lease。意外错误以 RuntimeException 抛出。
   */
  QuiesceOutcome quiesceAndRecheck(ThreadOwnership ownership, Instant now);

  /** best-effort 释放 lease：用于 typed failure、step-limit 失败或意外 RuntimeException 之后的兜底清理；适配器应吞掉内部异常。 */
  void bestEffortRelease(ThreadOwnership ownership, Instant now);
}
