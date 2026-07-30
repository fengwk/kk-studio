package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * ModelInvocation worker 的按用例原子事务端口。
 *
 * <p>实现不向 Runtime 暴露 Mapper、Repository、Spring transaction 或 Redis 类型。terminal mutation 必须按 {@code
 * Thread -> ModelInvocation} 锁序完成 Invocation fencing、terminal payload 与 {@code
 * Thread.runnable=true}；retry mutation 只能写 {@code RETRY_WAIT/nextAttemptAt}，不得提前激活 Thread。所有接收
 * {@code lastObservedActivityAt} 的 mutation 必须以当前 durable 值和入参的较大者落库；{@code now} 不是 Provider
 * progress，不能替代该入参。除 claim 外的 mutation 必须同时校验 Invocation id/status、execution epoch、attempt、worker
 * token 与 lease 有效期，不能只比较 token。
 */
public interface ModelInvocationTransactions {

  /**
   * 读取一个当前可 claim 的指定 Invocation。
   *
   * <p>返回值只能是 QUEUED、已到期 RETRY_WAIT，或 worker lease 已过期的 RUNNING Invocation。该读不是 ownership；随后必须调用
   * {@link #claim(long, String, ModelCallTimeoutPolicy, Duration, Instant)} 完成 fencing CAS。
   */
  Optional<ModelInvocation> findClaimable(long invocationId, Instant now);

  /**
   * 原子 claim Invocation。
   *
   * <p>QUEUED 首次进入 RUNNING 时建立 {@code startedAt/deadlineAt/lastActivityAt}；RETRY_WAIT 保留首次 建立的
   * {@code startedAt/deadlineAt}、递增 attempt，并把 {@code lastActivityAt} 刷新为本次 Provider attempt
   * 开始时刻以重新计量 idle timeout；接管已过期 RUNNING lease 时保留原 clocks 并返回 {@link
   * ClaimedModelInvocation#recoveredLease()}。所有成功结果均使用调用方为本次 claim 新生成的 {@code workerToken} 写入新
   * lease。
   */
  Optional<ClaimedModelInvocation> claim(
      long invocationId,
      String workerToken,
      ModelCallTimeoutPolicy timeoutPolicy,
      Duration workerLeaseDuration,
      Instant now);

  /** 在持有 claim 的情况下续租；该 mutation 绝不能更新真实 Provider progress activity。 */
  ModelInvocationUpdateOutcome renew(
      ClaimedModelInvocation claimed, Duration workerLeaseDuration, Instant now);

  /**
   * 记录真实 Provider delta 的 activity 时刻。lease heartbeat 不得调用此方法。
   *
   * <p>适配器可合并较旧 activity update，但不得把 {@code lastActivityAt} 倒退。
   */
  ModelInvocationUpdateOutcome recordActivity(
      ClaimedModelInvocation claimed, Instant activityAt, Instant now);

  /** 原子写入 SUCCEEDED result/最后已观察 delta activity，清除 worker lease 并标记 owning Thread runnable。 */
  ModelInvocationUpdateOutcome completeSuccess(
      ClaimedModelInvocation claimed,
      ProviderResponse result,
      Instant lastObservedActivityAt,
      Instant now);

  /** 原子写入最终 FAILED error/最后已观察 delta activity，清除 worker lease 并标记 owning Thread runnable。 */
  ModelInvocationUpdateOutcome completeFailure(
      ClaimedModelInvocation claimed,
      ModelInvocationError error,
      Instant lastObservedActivityAt,
      Instant now);

  /** 原子写入 CANCELLED/最后已观察 delta activity，清除 worker lease 并标记 owning Thread runnable。 */
  ModelInvocationUpdateOutcome completeCancelled(
      ClaimedModelInvocation claimed, Instant lastObservedActivityAt, Instant now);

  /** 原子写入 UNKNOWN error/最后已知 activity，清除 worker lease 并标记 owning Thread runnable。 */
  ModelInvocationUpdateOutcome completeUnknown(
      ClaimedModelInvocation claimed,
      ModelInvocationError error,
      Instant lastObservedActivityAt,
      Instant now);

  /**
   * 原子转入 RETRY_WAIT，清除 worker lease 并保留首次 RUNNING 建立的总 deadline。
   *
   * <p>retry 到期前不标记 owning Thread runnable；成功后调用方只应 reschedule MODEL_INVOCATION durable target。
   */
  ModelInvocationUpdateOutcome scheduleRetry(
      ClaimedModelInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now);

  /**
   * 原子 fenced 写入跨节点安全流快照（仅 text + thinking）；CAS 校验完整 fence，绝不更新 lease 与 deadline。
   *
   * <p>实现必须以当前 durable 快照与入参较大者落库（不允许倒退），且绝不发布 realtime delta 直到本次 mutation 返回 {@link
   * ModelInvocationUpdateOutcome#APPLIED}。仅允许在持有 claim 的 RUNNING Invocation 上调用；其他 status 一律返回
   * {@link ModelInvocationUpdateOutcome#LOST_OWNERSHIP}。tool-call fragment 永远不进入该方法。
   */
  ModelInvocationUpdateOutcome recordSafeStreamSnapshot(
      ClaimedModelInvocation claimed, SafeStreamSnapshot snapshot, Instant activityAt, Instant now);
}
