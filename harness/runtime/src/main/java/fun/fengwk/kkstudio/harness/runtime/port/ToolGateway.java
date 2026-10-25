package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tool 执行端口：preflight 权限判定、本地 admission 与阻塞执行。
 *
 * <p>{@link #preflight} 是同步、无副作用、事务外的判定；异常表示本次判定没有产生执行副作用，Processor 可安全 reschedule。它只接收 frozen
 * {@link ToolInvocationRequest}，不感知 Thread YOLO——YOLO 短路由 Processor 在锁内读取 Thread 后自行决定，本端口绝不查询
 * HarnessStore。Tool 的 retry 决策（{@link Failed#retryable} 后何时重试）由 Processor 决定，不放 Gateway。
 *
 * <p>{@link #admit} 是纯本地检查：只解析冻结目录、校验定义与环境、申请并发配额并钉住目录租用，不触碰任何外部系统，因此 {@link Busy} / {@link
 * Rejected} 肯定没有副作用。{@link Admitted} 持有的本地资源只由 {@link Run#close()} 释放：Processor 在终态决议提交之后、执行体
 * 返回（或抛出）之后调用它，这一顺序保证资源在工作线程退出前绝不会被回收。
 */
public interface ToolGateway {

  /** 一次 Tool execution 前的权限判定（不携带也不查询 YOLO）。 */
  PreflightResult preflight(ToolInvocationRequest request);

  /**
   * 本地 admission：返回 {@link Admitted}（已持有资源）、{@link Busy} 或 {@link Rejected}。抛异常表示 admission
   * 没有产生任何副作用（也不持有任何 资源），Processor 保持 READY 并 reschedule。
   */
  Admission admit(Execution execution);

  /**
   * 绑定当前 Tool 代次的远端执行观察者。
   *
   * <p>{@link #tryStart} 必须在发送前持久登记；Stop 或失权已先获胜时返回 false，调用方不得发送。 {@link #confirmExit} 只接受该次调用的正向
   * ACK；{@link #confirmNotStarted} 只用于确定未发送。发送是否发生不确定时两者都不能调用。
   */
  interface RemoteExecutionObserver {
    boolean tryStart(UUID remoteExecutionId);

    void confirmExit(UUID remoteExecutionId);

    void confirmNotStarted(UUID remoteExecutionId);
  }

  /**
   * 一次 Tool execution 的不可变描述：key 为 {@code (invocationId, threadId, attempt)}，request 已冻结。 {@code
   * assistantEntryId} 是所有 sibling 共享的冻结 branch basis；{@code threadId} 提供平台 ToolExecutionContext
   * 需要的持久线程所有权。{@code remoteExecutions} 只登记这一本地代次上的实际远端调用。
   */
  record Execution(
      UUID invocationId,
      UUID threadId,
      UUID assistantEntryId,
      int attempt,
      ToolInvocationRequest request,
      ToolApproval approval,
      RemoteExecutionObserver remoteExecutions) {

    public Execution {
      Objects.requireNonNull(invocationId, "invocationId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(assistantEntryId, "assistantEntryId");
      if (attempt < 1) {
        throw new IllegalArgumentException("attempt must be positive");
      }
      request = Objects.requireNonNull(request, "request");
      approval = Objects.requireNonNull(approval, "approval");
      remoteExecutions = Objects.requireNonNull(remoteExecutions, "remoteExecutions");
    }

    public ExpectedFile expectedFile() {
      ToolEditReview editReview = approval.editReview();
      return editReview == null ? null : editReview.expectedFile();
    }
  }

  /** preflight 结果；每种结果对应唯一确定的 durable approval transition。 */
  sealed interface PreflightResult permits ToolGateway.Allow, ToolGateway.Ask, ToolGateway.Deny {}

  /** 无需审批；可直接进入 admission。 */
  record Allow() implements PreflightResult {}

  /** 需要用户确认；reason 是人类可读的 canonical 提示。 */
  record Ask(String reason, ToolEditReview editReview) implements PreflightResult {

    public Ask(String reason) {
      this(reason, null);
    }

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
  sealed interface Admission permits ToolGateway.Admitted, ToolGateway.Busy, ToolGateway.Rejected {}

  /** 准入成功并已持有执行资源；调用方必须恰好调用一次 {@link Run#close()}。 */
  record Admitted(Run run) implements Admission {
    public Admitted {
      run = Objects.requireNonNull(run, "run");
    }
  }

  /** 本地容量暂时不足，肯定未执行；调用方按 {@code retryAfter} 重新调度（attempt 不变）。 */
  record Busy(Duration retryAfter) implements Admission {
    public Busy {
      retryAfter = HarnessStoreTime.requireWholeMillisecondDuration(retryAfter, "retryAfter");
    }
  }

  /** 肯定未执行且不可重试；调用方 terminate invocation 为 FAILED（attempt 不变）。 */
  record Rejected(ToolInvocationError error) implements Admission {
    public Rejected {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /**
   * 一次已准入的阻塞执行。{@link #execute} 在 Processor 的执行线程上运行直至终态：取消与租约丢失表现为对该线程的 interrupt，实现把它映射为 {@link
   * Cancelled}；工具异常由实现分类为 {@link Failed} / {@link Unknown}，绝不向外抛业务异常。
   */
  interface Run extends AutoCloseable {

    /** 阻塞执行并返回唯一终态；{@code progress} 接收已校验的非 terminal partial。 */
    Completion execute(Consumer<ToolResult> progress);

    /** 释放本次执行持有的本地资源（并发配额与目录租用）；幂等。释放失败必须传播，不能据此确认退出。 */
    @Override
    void close();
  }

  /** 一次执行的唯一终态。 */
  sealed interface Completion
      permits ToolGateway.Succeeded,
          ToolGateway.Failed,
          ToolGateway.Cancelled,
          ToolGateway.Unknown {}

  /** 已确认成功：模型可见结果与同一次 durable terminal success 的 branch effects。 */
  record Succeeded(ToolSuccess success) implements Completion {
    public Succeeded {
      success = Objects.requireNonNull(success, "success");
    }
  }

  /**
   * 已确认失败的 terminal 事实。{@code retryable} 是 Gateway 的明确判定：string kind 不足以让 Runtime
   * 安全推断能否重试；Processor 最终还结合 {@link fun.fengwk.kkstudio.harness.tool.ToolSideEffect}
   * 决定是否真的重试（NON_IDEMPOTENT 的失败即使 retryable 也不自动重试）。
   */
  record Failed(ToolInvocationError error, boolean retryable) implements Completion {
    public Failed {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 已确认被取消的 terminal 事实。 */
  record Cancelled(ToolInvocationError error) implements Completion {
    public Cancelled {
      error = Objects.requireNonNull(error, "error");
    }
  }

  /** 无法确认是否执行成功的 terminal error；调用方 terminate invocation 为 UNKNOWN，绝不重放。 */
  record Unknown(ToolInvocationError error) implements Completion {
    public Unknown {
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
