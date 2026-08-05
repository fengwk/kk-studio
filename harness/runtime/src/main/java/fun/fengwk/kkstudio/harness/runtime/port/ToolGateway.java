package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;

/**
 * Tool 执行 admission 端口：preflight 权限判定与 execution 提交。
 *
 * <p>{@link #preflight} 是同步、无副作用、事务外的判定；异常表示本次判定没有产生执行副作用，Processor 可安全 reschedule。{@code
 * yoloEnabled} 与 frozen {@link ToolInvocationRequest} 一起决定 Allow / Ask / Deny。{@link #start}
 * 返回前不得同步调用任何 listener 回调；回调 duplicate / stale 由 Runtime fence，Gateway 不保证 exactly-once。Tool 的
 * retry 决策（Busy / Overloaded 后何时重试）由 Processor 决定，不放 Gateway。
 */
public interface ToolGateway {

  /** 一次 Tool execution 前的权限判定。 */
  PreflightResult preflight(ToolInvocationRequest request, boolean yoloEnabled);

  /**
   * 提交一次 execution。只有当实现能证明外部 Gateway 尚未接受该 execution 时才允许抛异常：异常后 durable 仍是 DISPATCHING，Processor
   * 将 invocation 转回 READY 并 reschedule（attempt 不变）。如果发送 / 接受状态不确定 （execution 可能已开始），必须返回 {@link
   * Indeterminate}，绝不能抛。
   */
  StartResult start(Execution execution, Listener listener);

  /**
   * 一次 Tool execution 的不可变描述：key 为 {@code (invocationId, threadId, proposedAttempt)}，request 已冻结。
   * {@code threadId} 提供平台 ToolExecutionContext 需要的持久线程所有权，Gateway 无需查询 HarnessStore。
   */
  record Execution(
      long invocationId, long threadId, int proposedAttempt, ToolInvocationRequest request) {

    public Execution {
      if (invocationId <= 0) {
        throw new IllegalArgumentException("invocationId must be positive");
      }
      if (threadId <= 0) {
        throw new IllegalArgumentException("threadId must be positive");
      }
      if (proposedAttempt <= 0) {
        throw new IllegalArgumentException("proposedAttempt must be positive");
      }
      request = Objects.requireNonNull(request, "request");
    }
  }

  /** preflight 结果；每种结果对应唯一确定的 durable approval transition。 */
  sealed interface PreflightResult permits ToolGateway.Allow, ToolGateway.Ask, ToolGateway.Deny {}

  /** 无需审批；可直接 beginDispatch。 */
  record Allow() implements PreflightResult {}

  /** 需要用户确认；reason 是人类可读的 canonical 提示。 */
  record Ask(String reason) implements PreflightResult {
    public Ask {
      reason = requireCanonicalReason(reason);
    }
  }

  /** 确定性拒绝；调用方 terminate invocation 为 FAILED。 */
  record Deny(ToolInvocationError error) implements PreflightResult {
    public Deny {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** admission 结果；每个结果对应唯一确定的 durable 后续 transition。 */
  sealed interface StartResult
      permits ToolGateway.Started,
          ToolGateway.Busy,
          ToolGateway.Overloaded,
          ToolGateway.Rejected,
          ToolGateway.Indeterminate {}

  /** 调用已接受并开始；{@code handle} 提供 best effort 取消。 */
  record Started(Handle handle) implements StartResult {
    public Started {
      handle = Objects.requireNonNull(handle, "handle");
    }
  }

  /** 肯定未开始；调用方应稍后按 {@code retryAfter} 重新 dispatch（attempt 不变）。 */
  record Busy(Duration retryAfter) implements StartResult {
    public Busy {
      retryAfter = HarnessStoreTime.requireWholeMillisecondDuration(retryAfter, "retryAfter");
    }
  }

  /** 肯定未开始；Gateway 过载，调用方按 {@code retryAfter} 重新 dispatch（attempt 不变）。 */
  record Overloaded(Duration retryAfter) implements StartResult {
    public Overloaded {
      retryAfter = HarnessStoreTime.requireWholeMillisecondDuration(retryAfter, "retryAfter");
    }
  }

  /** 肯定未开始且不可重试；调用方 terminate invocation（attempt 不变）。 */
  record Rejected(ToolInvocationError error) implements StartResult {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 可能已开始；调用方 terminate invocation 为 UNKNOWN（attempt + 1）。 */
  record Indeterminate(ToolInvocationError error) implements StartResult {
    public Indeterminate {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 一次 execution 的本地取消控制；best effort 且幂等，不保证远程停止。 */
  interface Handle {
    void cancel();
  }

  /** partial 与 terminal 回调；duplicate / stale 由 Runtime fence。 */
  interface Listener {

    /** 交付一个非 terminal partial ToolResult（不得包含 binary / resource content）。 */
    void onPartial(ToolResult partial);

    /** 交付完整 ToolResult。 */
    void onSucceeded(ToolResult result);

    /** 交付已确认失败的 terminal 事实（含明确 retryable 标记）。 */
    void onFailed(Failure failure);

    /** 交付已确认被取消的 terminal 事实（string kind 不足以可靠推导，必须显式回调）。 */
    void onCancelled(ToolInvocationError error);

    /** 交付无法确认是否执行成功的 terminal error。 */
    void onUnknown(ToolInvocationError error);
  }

  /**
   * 已确认失败的 terminal 事实。{@code retryable} 是 Gateway 的明确判定：string kind 不足以让 Runtime 安全推断
   * 能否重试；Processor 最终还结合 {@link fun.fengwk.kkstudio.harness.tool.ToolSideEffect} 决定是否真的重试 （例如
   * NON_IDEMPOTENT 的失败即使 retryable 也不自动重试）。
   */
  record Failure(ToolInvocationError error, boolean retryable) {

    public Failure {
      error = Objects.requireNonNull(error, "error");
    }
  }

  private static String requireCanonicalReason(String value) {
    Objects.requireNonNull(value, "reason");
    if (value.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("reason must not contain surrounding whitespace");
    }
    if (value.length() > 1024) {
      throw new IllegalArgumentException("reason must be <= 1024 characters");
    }
    return value;
  }
}
