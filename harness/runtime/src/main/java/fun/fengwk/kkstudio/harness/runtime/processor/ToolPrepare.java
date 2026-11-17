package fun.fengwk.kkstudio.harness.runtime.processor;

import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputTool;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.spi.ToolGateway.ExecutionScope;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedExecutionSupport;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tool prepare 阶段：在 own Thread -&gt; ToolInvocation -&gt; Work 的锁序内读取 durable 状态，并决定本次 claim
 * 应该执行哪一个动作。
 *
 * <p>这里只读取与冻结事实：不调用 gateway、不启动执行线程。人工输入与失联恢复等需要写回状态的收敛分支在同一事务内完成并返回 {@link
 * Step.Terminated}；执行、preflight 与重排由 {@link ToolProcessor} 承担。
 */
final class ToolPrepare {

  /** 冻结问卷无法解析时的确定性错误 kind：契约漂移，可重试前提是工具实现修好。 */
  private static final String INVALID_QUESTIONNAIRE_KIND = "INVALID_QUESTIONNAIRE";

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();
  private static final ObjectMapper TASK_RESULT_JSON = new ObjectMapper();

  private final HarnessStore store;
  private final ClaimedExecutionSupport<ToolExecution> executions;

  ToolPrepare(HarnessStore store, ClaimedExecutionSupport<ToolExecution> executions) {
    this.store = Objects.requireNonNull(store, "store");
    this.executions = Objects.requireNonNull(executions, "executions");
  }

  /** 按锁序 Thread -&gt; Tool -&gt; Work 读取并分支当前 durable 状态；不重放、不重复 bump version。 */
  Step prepare(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    UUID invocationId = claim.target().id();
    ToolInvocation peek = tx.tools().find(invocationId).orElse(null);
    if (peek == null) {
      return new Step.Lost();
    }
    ThreadState thread = tx.threads().lock(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Step.Lost();
    }
    ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
    if (tool == null) {
      return new Step.Lost();
    }
    // 公共工具归属与能力已冻结在 TurnStart；执行路径不读取/锁定 Driver 私有行。
    if (!tool.threadId().equals(thread.id())) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its thread during preparation");
    }
    return switch (tool.status()) {
      case READY -> prepareReady(tx, claim, thread, tool, leaseConfig);
      case WAITING_APPROVAL, WAITING_INPUT -> waitingForHuman(tx, claim);
      case WAITING_CHILD -> {
        ThreadJoin join =
            tx.joins()
                .find(tool.id())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "WAITING_CHILD task lost its durable Join " + tool.id()));
        SubagentTaskRequest.requireMatchingJoin(tool, join);
        yield settleTaskJoin(tx, claim, thread, tool, join, now);
      }
      case RUNNING -> prepareRunning(tx, claim, thread, tool, now, leaseConfig);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread);
    };
  }

  /**
   * READY：内部人工输入工具在锁内直接冻结为 WAITING_INPUT；其它调用 approval null 走 preflight（ensure 完整 lease margin
   * 后事务外执行，期间 heartbeat 维持 lease）；completed approval 直接进入执行。
   *
   * <p>YOLO 决策在锁内完成：只锁本 Thread 后解析的实际开关为 true 时直接返回 {@link Step.Allowed}（一次 preflight 只做一次控制决定，不调用
   * gateway / evaluator，后续切换不追溯已完成的派发）；false 才走普通 gateway preflight。 人输入判定先于 YOLO 与 preflight：YOLO
   * 不代替用户作答，问卷也不经过工具权限审批。
   */
  private Step prepareReady(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      ProcessorLeaseConfig leaseConfig) {
    Optional<Work> claimed = tx.work().lockClaimed(claim);
    if (claimed.isEmpty()) {
      return new Step.Lost();
    }
    if (HumanInputTool.isHumanInputTool(tool.binding())) {
      return new Step.Input(thread.id());
    }
    // admission 前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    executions.ensureLeaseMargin(tx, claim, leaseConfig);
    // READY 边界临时构造 transient executable request（不持久化）。
    ToolInvocationRequest request = new ToolInvocationRequest(tool.call(), tool.binding());
    // 执行上下文事实只在锁内读取一次：Thread/会话/执行根取自已锁定行，assistant 分支与媒体能力取该调用的冻结依据。
    ExecutionScope scope = executionScope(tx, thread, tool);
    if (tool.approval() == null) {
      if (effectiveYoloEnabled(tx, thread)) {
        // YOLO=true：锁内快照直接 Allow，绝不调用 permission evaluator / ToolGateway.preflight。
        return new Step.Allowed(scope, tool.attempt(), request);
      }
      return new Step.Preflight(scope, tool.attempt(), request);
    }
    return new Step.Dispatched(scope, tool.attempt(), request, tool.approval());
  }

  /**
   * 冻结本次执行的上下文事实：Thread 自身会话、不可变执行根与其会话、该调用的 assistant 分支入口，以及该调用 TURN_START 已冻结的 消费者媒体能力。
   *
   * <p>事实全部来自当前已锁定 Thread 与该调用 assistant path 的同一事务读取，因此 admission 与工具执行无需查询 Harness 存储。执行根会话
   * 从真实执行根 Thread 行读取，缺失时显式失败，绝不按当前 Thread 合成。
   *
   * <p>任一读取抛出 {@link RuntimeException} 时包装为 {@link ExecutionScopeReadFailure} 重抛，使 prepare
   * 事务完整回滚，再由 {@link ToolProcessor} 按 admission 失败路径有界重排；事实本身绝不回落默认值。
   */
  private static ExecutionScope executionScope(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    try {
      return readExecutionScope(tx, thread, tool);
    } catch (RuntimeException failure) {
      throw new ExecutionScopeReadFailure(thread.id(), tool.attempt(), failure);
    }
  }

  /** 上下文事实的实际读取；读取失败由 {@link #executionScope} 统一包装为回滚信号。 */
  private static ExecutionScope readExecutionScope(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    UUID rootThreadId = thread.rootThreadId();
    UUID rootSessionId =
        rootThreadId.equals(thread.id())
            ? thread.sessionId()
            : tx.threads()
                .find(rootThreadId)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "thread " + thread.id() + " lost its execution root " + rootThreadId))
                .sessionId();
    return new ExecutionScope(
        thread.id(),
        thread.sessionId(),
        rootThreadId,
        rootSessionId,
        tool.assistantEntryId(),
        frozenMediaCapabilities(tx, thread, tool));
  }

  /** 消费者媒体能力的唯一来源是该调用 TURN_START 冻结的能力快照；绝不读取 live 模型或 Driver 私有状态。 */
  private static TurnMediaCapabilities frozenMediaCapabilities(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    Entry turnStart =
        tx.entries()
            .find(tool.turnStartEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool invocation "
                            + tool.id()
                            + " lost its turn start entry "
                            + tool.turnStartEntryId()));
    if (!(turnStart.payload() instanceof TurnStartPayload start)
        || !start.ownerThreadId().equals(thread.id())
        || start.capabilities() == null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " has no frozen turn capabilities for this thread");
    }
    return start.capabilities().mediaCapabilities();
  }

  /**
   * WAITING_APPROVAL / WAITING_INPUT 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump version、不
   * request THREAD。审批与问卷由用户侧显式决策/作答唤醒。
   */
  private static Step waitingForHuman(HarnessStore.Transaction tx, ClaimedWork claim) {
    if (tx.work().lockClaimed(claim).isEmpty()) {
      return new Step.Lost();
    }
    tx.work().complete(claim);
    return new Step.Terminated();
  }

  /**
   * RUNNING 判定在途远端执行： 1) 工具有冻结 binding 且带 environmentId； 2) loadRemote(invocationId, attempt)
   * 恰好返回一条事实； 3) 该事实状态为 UNCONFIRMED 或 REMOTE_ACKNOWLEDGED。 满足条件则进入重新挂接（reattach）流程；否则沿用
   * recoverUnknown 收敛为 UNKNOWN。
   */
  private Step prepareRunning(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    if ("task".equals(tool.call().toolName())) {
      ThreadJoin join = tx.joins().find(tool.id()).orElse(null);
      if (join != null) {
        SubagentTaskRequest.requireMatchingJoin(tool, join);
        return settleTaskJoin(tx, claim, thread, tool, join, now);
      }
    }
    if (tool.binding().environmentId() != null) {
      List<ExecutionExitFact> remotes = tx.exits().loadRemote(tool.id(), tool.attempt());
      if (remotes.size() == 1) {
        ExecutionExitFact remoteFact = remotes.getFirst();
        if (remoteFact.state() == ExecutionExitState.UNCONFIRMED
            || remoteFact.state() == ExecutionExitState.REMOTE_ACKNOWLEDGED) {
          Optional<Work> claimed = tx.work().lockClaimed(claim);
          if (claimed.isEmpty()) {
            return new Step.Lost();
          }
          executions.ensureLeaseMargin(tx, claim, leaseConfig);
          ToolInvocationRequest request = new ToolInvocationRequest(tool.call(), tool.binding());
          // 挂接只连接既有远端执行，不复用 admission 资源、也不使用执行上下文事实：这里不读取分支 / TURN_START / 执行根。
          return new Step.Reattach(thread.id(), tool.attempt(), request, remoteFact);
        }
      }
    }
    return recoverUnknown(tx, claim, thread, tool, now);
  }

  /**
   * RUNNING recovery and WAITING_CHILD share one Join-driven settle path; only RUNNING must be
   * parked.
   */
  private static Step settleTaskJoin(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      ThreadJoin join,
      Instant now) {
    if (thread.executionControl().isStopped()) {
      if (tx.work().lockClaimed(claim).isEmpty()) {
        return new Step.Lost();
      }
      tx.tools()
          .update(
              List.of(
                  tool.cancel(
                      new ToolInvocationError(
                          "PARENT_STOPPED", "parent thread stopped while task was waiting"),
                      now)));
      tx.threads().update(thread.touchVersion(now));
      tx.work().complete(claim);
      return new Step.Terminated();
    }
    if (join.supersededByInvocationId() != null) {
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
      if (tx.work().lockClaimed(claim).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.tools()
          .update(
              List.of(
                  tool.fail(
                      new ToolInvocationError(
                          "TASK_SUPERSEDED", "task Join was superseded by a later delegation"),
                      now)));
      tx.threads().update(thread.touchVersion(now));
      tx.work().complete(claim);
      return new Step.Terminated();
    }
    if (join.matched()) {
      ThreadJoinReceipt receipt =
          ThreadJoinProjector.INSTANCE
              .project(tx, join)
              .orElseThrow(() -> new IllegalStateException("matched task Join has no receipt"));
      return settleMatchedTask(tx, claim, thread, tool, join, receipt, now);
    }
    if (tx.work().lockClaimed(claim).isEmpty()) {
      return new Step.Lost();
    }
    if (tool.status() == ToolInvocationStatus.RUNNING) {
      tx.tools().update(List.of(tool.waitForChild(now)));
      tx.threads().update(thread.touchVersion(now));
    }
    tx.work().complete(claim);
    return new Step.Terminated();
  }

  private static Step settleMatchedTask(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      ThreadJoin join,
      ThreadJoinReceipt receipt,
      Instant now) {
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    UUID sessionId =
        tx.threads()
            .find(join.childThreadId())
            .orElseThrow(() -> new IllegalStateException("task child Thread disappeared"))
            .sessionId();
    tx.tools()
        .update(
            List.of(
                tool.succeed(
                    SubagentTaskRequest.resultFromJoin(
                        TASK_RESULT_JSON, tool.call().id(), sessionId, join, receipt),
                    now)));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Step.Terminated();
  }

  /** 过期租约恢复：执行持有者已失效，绝不重放 Tool。RUNNING 表示可能已产生副作用；如实给出中断原因与最后已知状态，并明确外部效果未知， 交给下一轮模型自行决定查询或重试。 */
  private static Step recoverUnknown(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    ToolInvocationError error = leaseLostError(tool.status());
    tx.tools().update(List.of(tool.unknown(error, now)));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Step.Terminated();
  }

  /** 失联恢复的如实反馈：只报告可证实的原因与最后已知状态，并明确外部效果未知；不把未知效果写成“确定未执行”。 */
  private static ToolInvocationError leaseLostError(ToolInvocationStatus lastKnownStatus) {
    return new ToolInvocationError(
        "LEASE_EXPIRED",
        "tool invocation was interrupted because its execution holder was lost; no persisted final"
            + " result was obtained. Last known status: "
            + lastKnownStatus
            + ". Whether the external operation took effect, or is still running, cannot be"
            + " confirmed by this result.");
  }

  /**
   * 内部人工输入：READY -&gt; WAITING_INPUT，version+1，complete TOOL Work，不 request THREAD、不进入 preflight /
   * gateway。
   *
   * <p>问卷必须在接受时就能解析：解析失败说明 {@code ask_user} 契约被破坏（工具实现与运行时契约漂移），此时以确定性失败收敛并唤醒
   * Thread，让模型看到无效调用，而不是冻结一个无法作答的等待。提交前按 own Thread 锁序锁定后二次校验 claim + READY + attempt 0 + approval
   * null，lost 完整 no-op。错误信息只报告原因，不回显问卷原文。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean freezeInput(ClaimedWork claim, UUID threadId) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
              if (tool == null || !isFrozenReadyInput(tool)) {
                return false;
              }
              // 破坏契约的问卷以失败收敛并唤醒 Thread；Work 锁必须按 (type, id) 升序获取，因此先 request THREAD。
              boolean answerable = isFrozenQuestionnaire(tool);
              if (!answerable) {
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
              }
              if (tx.work().lockClaimed(claim).isEmpty()) {
                throw new ClaimLostSignal();
              }
              tx.tools()
                  .update(
                      List.of(
                          answerable
                              ? tool.requestInput(now)
                              : tool.fail(
                                  new ToolInvocationError(
                                      INVALID_QUESTIONNAIRE_KIND,
                                      "ask_user invocation does not carry a valid questionnaire"),
                                  now)));
              tx.threads().update(thread.touchVersion(now));
              tx.work().complete(claim);
              return true;
            }));
  }

  /** 只有尚未审批、尚未执行（READY + attempt 0 + approval null）的调用才能被冻结为等待或确定性失败。 */
  private static boolean isFrozenReadyInput(ToolInvocation tool) {
    return tool.status() == ToolInvocationStatus.READY
        && tool.attempt() == 0
        && tool.approval() == null;
  }

  /** 冻结问卷是否可解析；失败只说明契约被破坏，绝不写回问题原文。 */
  private static boolean isFrozenQuestionnaire(ToolInvocation tool) {
    try {
      INPUT_JSON.decodeQuestionnaire(tool.call().argumentsJson());
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /**
   * terminal 行：始终确保 THREAD Work 请求（outcome 尚未物化才可能有 terminal 行），然后 complete TOOL Work；不重复 bump
   * version。
   */
  private static Step cleanupTerminal(
      HarnessStore.Transaction tx, ClaimedWork claim, ThreadState thread) {
    // requestWork 同时 upsert 并锁定 THREAD Work；即使原行不存在，也能建立正确锁序。
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
    return new Step.Terminated();
  }

  /** 有效 YOLO 只由执行根的开关决定；子代理读取其不可变执行根。 */
  private static boolean effectiveYoloEnabled(HarnessStore.Transaction tx, ThreadState thread) {
    if (thread.rootThreadId().equals(thread.id())) {
      return thread.yoloEnabled();
    }
    return tx.threads().find(thread.rootThreadId()).orElseThrow().yoloEnabled();
  }

  /** prepare 阶段对本次 claim 的唯一结论。 */
  sealed interface Step
      permits Step.Lost,
          Step.Input,
          Step.Preflight,
          Step.Allowed,
          Step.Dispatched,
          Step.Reattach,
          Step.Terminated {

    record Lost() implements Step {}

    /** 内部人工输入工具：READY 边界已冻结为 WAITING_INPUT，不进入 preflight / gateway。 */
    record Input(UUID threadId) implements Step {}

    /** 普通 gateway preflight（锁内 YOLO 快照为 false）。 */
    record Preflight(ExecutionScope scope, int attempt, ToolInvocationRequest request)
        implements Step {}

    /** 锁内 YOLO 快照为 true 的直接 Allow：不调用 gateway preflight，transient 决策标志。 */
    record Allowed(ExecutionScope scope, int attempt, ToolInvocationRequest request)
        implements Step {}

    record Dispatched(
        ExecutionScope scope, int attempt, ToolInvocationRequest request, ToolApproval approval)
        implements Step {}

    /** 远端重新挂接：只需要所属 Thread id，不携带执行上下文快照。 */
    record Reattach(
        UUID threadId, int attempt, ToolInvocationRequest request, ExecutionExitFact remoteFact)
        implements Step {}

    record Terminated() implements Step {}
  }

  /**
   * 执行上下文事实（会话 / 执行根 / 分支视图 / 冻结媒体能力）在 prepare 事务内读取失败的回滚信号。
   *
   * <p>只在 {@link #executionScope} 内产生：携带已锁定的 Thread 与 attempt，使 {@link ToolProcessor} 能在事务回滚后按
   * admission 失败路径锁后重新校验并重排；它不表达持久事实，原始失败保留为 cause，绝不被吞掉。
   */
  static final class ExecutionScopeReadFailure extends RuntimeException {

    private final UUID threadId;
    private final int attempt;

    ExecutionScopeReadFailure(UUID threadId, int attempt, RuntimeException cause) {
      super("tool execution scope read failed", cause);
      this.threadId = Objects.requireNonNull(threadId, "threadId");
      this.attempt = attempt;
    }

    UUID threadId() {
      return threadId;
    }

    int attempt() {
      return attempt;
    }
  }
}
