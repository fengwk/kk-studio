package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

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

  /**
   * 一次 Tool execution 前的权限判定（不携带也不查询 YOLO）。
   *
   * <p>{@code scope} 是该调用冻结的执行根事实：需要审批的 {@code vfs:} 编辑据此读取当前会话文件并生成审查预览。除只读预览外，判定本身不产生 执行副作用。
   */
  PreflightResult preflight(ToolInvocationRequest request, ExecutionScope scope);

  /**
   * 本地 admission：返回 {@link Admitted}（已持有资源）、{@link Busy} 或 {@link Rejected}。抛异常表示 admission
   * 没有产生任何副作用（也不持有任何 资源），Processor 保持 READY 并 reschedule。
   */
  Admission admit(Execution execution);

  /**
   * 重新挂接一个在途的远端 Capability 执行。
   *
   * <p>挂接只把本地执行体接到该次远端执行上：不重新 admission、不需要执行上下文事实，因此实现也不读取分支视图、会话或媒体能力。
   *
   * @param execution 挂接执行描述
   * @return 已准入的阻塞执行句柄
   * @throws EnvironmentCapabilityUnavailableException 发送前目标不可用（如本节点不持有该 Environment 的 READY 租约）
   */
  Run reattach(ReattachExecution execution) throws EnvironmentCapabilityUnavailableException;

  /**
   * 绑定当前 Tool 代次的远端执行观察者。
   *
   * <p>{@link #tryStart} 必须在发送前持久登记；Stop 或失权已先获胜时返回 false，调用方不得发送。 {@link #confirmExit} 只接受该次调用的正向
   * ACK；{@link #confirmNotStarted} 只用于确定未发送。发送是否发生不确定时两者都不能调用。
   */
  interface RemoteExecutionObserver {
    boolean tryStart(UUID remoteExecutionId, EnvironmentId environmentId);

    void confirmExit(UUID remoteExecutionId);

    void confirmNotStarted(UUID remoteExecutionId);
  }

  /**
   * 一次 Tool execution 所属的冻结 Harness 上下文事实：Thread、会话、执行根、该调用 assistant 分支的入口 Entry 与消费者媒体能力。
   *
   * <p>全部事实来自 Processor 对 own Thread、执行根与该调用 assistant path 的同一事务读取。执行网关、Plugin 与产品不得为了这些事实 再查询
   * Harness 存储：{@code assistantEntryId} 是该调用在 Thread 历史中的真实位置，{@code mediaCapabilities} 唯一来源是冻结的
   * TURN_START 能力快照。
   */
  record ExecutionScope(
      UUID threadId,
      UUID sessionId,
      UUID rootThreadId,
      UUID rootSessionId,
      UUID assistantEntryId,
      TurnMediaCapabilities mediaCapabilities) {

    public ExecutionScope {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(rootThreadId, "rootThreadId");
      Objects.requireNonNull(rootSessionId, "rootSessionId");
      Objects.requireNonNull(assistantEntryId, "assistantEntryId");
      mediaCapabilities = Objects.requireNonNull(mediaCapabilities, "mediaCapabilities");
    }
  }

  /**
   * 一次 Tool execution 的不可变描述：key 为 {@code (invocationId, threadId, attempt)}，request 已冻结。 {@code
   * scope} 携带该调用所属的 Thread/会话/执行根、该调用的 assistant 入口、assistant 分支视图与冻结媒体能力，平台据此装配 {@code
   * ToolExecutionContext}，不查询 Harness 存储。{@code remoteExecutions} 只登记这一本地代次上的实际远端调用。
   */
  record Execution(
      UUID invocationId,
      ExecutionScope scope,
      int attempt,
      ToolInvocationRequest request,
      ToolApproval approval,
      RemoteExecutionObserver remoteExecutions) {

    public Execution {
      Objects.requireNonNull(invocationId, "invocationId");
      scope = Objects.requireNonNull(scope, "scope");
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

  /**
   * 一次重新挂接远端执行的不可变描述。
   *
   * <p>挂接不复用本地 admission 资源，也不需要 {@link ExecutionScope} 的上下文事实：分支视图、执行根与媒体能力都不会被挂接使用，因此这里只携带 所属
   * Thread id。
   *
   * @param invocationId Tool invocation id
   * @param threadId 该调用所属 Thread id
   * @param request 冻结的 Tool 调用请求
   * @param environmentId 远端 Environment 路由身份
   * @param remoteExecutionId 原 owner 发送给远端 Capability 的执行 id
   * @param timeout 挂接等待上限（通常为 reattachWindow）
   * @param remoteExecutions 远端执行观察器（用于确认退出等）
   */
  record ReattachExecution(
      UUID invocationId,
      UUID threadId,
      ToolInvocationRequest request,
      EnvironmentId environmentId,
      UUID remoteExecutionId,
      Duration timeout,
      RemoteExecutionObserver remoteExecutions) {

    public ReattachExecution {
      Objects.requireNonNull(invocationId, "invocationId");
      threadId = Objects.requireNonNull(threadId, "threadId");
      request = Objects.requireNonNull(request, "request");
      environmentId = Objects.requireNonNull(environmentId, "environmentId");
      remoteExecutionId = Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
      timeout = HarnessStoreTime.requireWholeMillisecondDuration(timeout, "timeout");
      remoteExecutions = Objects.requireNonNull(remoteExecutions, "remoteExecutions");
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
