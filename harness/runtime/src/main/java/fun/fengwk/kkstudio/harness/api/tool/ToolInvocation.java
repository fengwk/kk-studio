package fun.fengwk.kkstudio.harness.api.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Tool Invocation 的持久化当前状态。
 *
 * <p>直接持有冻结的 {@code call} 与可空 {@code binding}，不保留冗余的持久化 request 包装；{@code inputReceipt} 只在
 * 内部人工输入工具（{@code ask_user}）被回答后随 SUCCEEDED 出现，是该回答的 durable 提交回执。{@code attempt} 统计已进入 RUNNING
 * 的执行次数；Busy 或执行前的本地拒绝不会增加它。result 与 error 在任何状态上都互斥；非 terminal 状态永远不携带 terminal 事实。Terminal Tool
 * 行始终表示 outcome 尚未进入 ToolResult Entry：batch apply 后行被物理删除。
 *
 * <p>{@code READY -> RUNNING} 在任何副作用之前持久化并消耗一次 attempt：RUNNING 之后的崩溃恢复一律收敛为 UNKNOWN，绝不重放。Store
 * 必须在每次 {@code update*} 写入前调用 {@link #validateTransition}。
 */
public record ToolInvocation(
    UUID id,
    UUID threadId,
    UUID turnStartEntryId,
    UUID assistantEntryId,
    int callIndex,
    ToolCall call,
    ToolBinding binding,
    ToolInvocationStatus status,
    int attempt,
    ToolApproval approval,
    ToolResult result,
    ToolInvocationError error,
    Instant createdAt,
    Instant updatedAt,
    ToolInputReceipt inputReceipt) {

  /** 构造不携带人工输入回执的 invocation；普通 Tool 与非成功状态使用此便捷入口。 */
  public ToolInvocation(
      UUID id,
      UUID threadId,
      UUID turnStartEntryId,
      UUID assistantEntryId,
      int callIndex,
      ToolCall call,
      ToolBinding binding,
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        threadId,
        turnStartEntryId,
        assistantEntryId,
        callIndex,
        call,
        binding,
        status,
        attempt,
        approval,
        result,
        error,
        createdAt,
        updatedAt,
        null);
  }

  public ToolInvocation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    if (callIndex < 0) {
      throw new IllegalArgumentException("callIndex must not be negative");
    }
    call = Objects.requireNonNull(call, "call");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    ToolInvocationValidation.validateFields(
        status, attempt, approval, result, error, call, inputReceipt);
    ToolInvocationValidation.validateBindingConstraint(status, attempt, call, binding);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /**
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 call/binding 不可变，updatedAt 不允许回退， attempt
   * 仅在 READY-&gt;RUNNING 时恰好 +1；approval 仅能以 {@code not-required} 引入到 READY-&gt;READY，或以 required
   * undecided request 引入到 READY-&gt;WAITING_APPROVAL；undecided approval 仅能被决定（ALLOWED -&gt;
   * READY，DENIED -&gt; FAILED） 或精确 replay；已决定 / non-required approval 不可变；terminal 事实不可变。精确 replay
   * 始终被接受。
   */
  public static void validateTransition(ToolInvocation stored, ToolInvocation next) {
    ToolInvocationValidation.validateTransition(stored, next);
  }

  /**
   * READY 且无 approval -&gt; READY，但带有 {@code not-required} approval；后续决策不可能，因此 dispatch 不需要
   * approval 往返。
   */
  public ToolInvocation markApprovalNotRequired(Instant now) {
    if (approval != null) {
      throw new IllegalArgumentException("approval already exists");
    }
    return withState(
        ToolInvocationStatus.READY, attempt, ToolApproval.notRequired(), null, null, now);
  }

  /** READY 且无 approval -&gt; WAITING_APPROVAL，并附带一个 required undecided approval。 */
  public ToolInvocation requestApproval(String reason, Instant now) {
    return requestApproval(reason, null, now);
  }

  public ToolInvocation requestApproval(String reason, ToolEditReview editReview, Instant now) {
    if (approval != null) {
      throw new IllegalArgumentException("approval already exists");
    }
    Instant effectiveNow = now;
    return withState(
        ToolInvocationStatus.WAITING_APPROVAL,
        attempt,
        ToolApproval.request(effectiveNow, reason, editReview),
        null,
        null,
        effectiveNow);
  }

  /**
   * READY（attempt 0、无 approval） -&gt; WAITING_INPUT：内部人工输入工具已接受调用，冻结问卷等待用户回答。
   *
   * <p>等待不执行、不消耗 attempt，也不产生 approval：问卷不能授权工具，YOLO 也不代替用户回答。
   */
  public ToolInvocation requestInput(Instant now) {
    if (status != ToolInvocationStatus.READY || attempt != 0 || approval != null) {
      throw new IllegalArgumentException(
          "requestInput requires a READY invocation with attempt 0 and no approval");
    }
    return withState(ToolInvocationStatus.WAITING_INPUT, attempt, null, null, null, now);
  }

  /** RUNNING -&gt; WAITING_CHILD：task Join 已接受，释放执行线程，等待持久 Join 结果。 */
  public ToolInvocation waitForChild(Instant now) {
    if (status != ToolInvocationStatus.RUNNING || !"task".equals(call.toolName())) {
      throw new IllegalArgumentException("waitForChild requires a running task invocation");
    }
    return withState(ToolInvocationStatus.WAITING_CHILD, attempt, approval, null, null, now);
  }

  /**
   * WAITING_INPUT -&gt; SUCCEEDED：用户已提交冻结问卷的答案（或明确拒答），结果与 durable 回执同事务落盘。
   *
   * <p>人工作答不是执行：attempt 保持 0、不携带 approval；回执只用于结果进入 Entry 前的重试与崩溃恢复。
   */
  public ToolInvocation acceptInput(ToolResult result, ToolInputReceipt receipt, Instant now) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(receipt, "receipt");
    if (status != ToolInvocationStatus.WAITING_INPUT) {
      throw new IllegalArgumentException("acceptInput requires WAITING_INPUT status");
    }
    return withState(ToolInvocationStatus.SUCCEEDED, attempt, null, result, null, receipt, now);
  }

  /**
   * 应用 approval 决策：ALLOWED 将 invocation 恢复为 READY，DENIED 将其终止为 FAILED。相同 decisionId 携带完全相同的决策
   * payload 是幂等的；相同 id 携带不同 payload 或与已有不同决策冲突。
   */
  public ToolInvocation decideApproval(
      ToolApprovalDecision decision,
      UUID decisionId,
      String actor,
      String reason,
      Instant decidedAt,
      Instant now) {
    Objects.requireNonNull(decision, "decision");
    if (approval == null) {
      throw new IllegalArgumentException("approval does not exist");
    }
    ToolApproval nextApproval = approval.decide(decision, decisionId, actor, reason, decidedAt);
    if (nextApproval.decision() == ToolApprovalDecision.ALLOWED) {
      return withState(ToolInvocationStatus.READY, attempt, nextApproval, null, null, now);
    }
    return withState(
        ToolInvocationStatus.FAILED,
        attempt,
        nextApproval,
        null,
        new ToolInvocationError("DENIED", reason == null ? "denied by user" : reason),
        now);
  }

  /**
   * READY -&gt; RUNNING：本地 admission 已通过，副作用之前持久化执行意图；要求预检 approval 已完成（非 null 且为 not-required 或
   * ALLOWED）；attempt 恰好 +1。
   */
  public ToolInvocation markRunning(Instant now) {
    if (status != ToolInvocationStatus.READY || !hasCompletedPreflightApproval()) {
      throw new IllegalArgumentException(
          "markRunning requires a READY invocation with a completed preflight approval");
    }
    return withState(
        ToolInvocationStatus.RUNNING, Math.addExact(attempt, 1), approval, null, null, now);
  }

  /** RUNNING -&gt; READY：执行端报告了可重试的失败，因此同一个 attempt 被从头重新调度；attempt 与 已完成的预检 approval 保持已确认。 */
  public ToolInvocation retryReady(Instant now) {
    if (status != ToolInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    return withState(ToolInvocationStatus.READY, attempt, approval, null, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED，并附带完整的 ToolResult；attempt 必须为正数。 */
  public ToolInvocation succeed(ToolResult result, Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(ToolInvocationStatus.SUCCEEDED, attempt, approval, result, null, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED，并附带一个 terminal error；attempt 不变。WAITING_APPROVAL 被拒绝：undecided
   * approval 只能通过已决定的 DENIED 决策来拒绝，或被作为 CANCELLED 终止。
   */
  public ToolInvocation fail(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ToolInvocationStatus.READY
        && status != ToolInvocationStatus.RUNNING
        && status != ToolInvocationStatus.WAITING_CHILD) {
      throw new IllegalArgumentException(
          "fail requires READY, RUNNING, or WAITING_CHILD status; WAITING_APPROVAL must be decided or stopped");
    }
    return withState(ToolInvocationStatus.FAILED, attempt, approval, null, error, now);
  }

  /**
   * WAITING_APPROVAL / WAITING_INPUT / WAITING_CHILD / READY / RUNNING -&gt; CANCELLED，并附带一个
   * terminal error；attempt 不变。
   */
  public ToolInvocation cancel(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    return withState(ToolInvocationStatus.CANCELLED, attempt, approval, null, error, now);
  }

  /** RUNNING -&gt; UNKNOWN，并附带一个 terminal error：不确定的结果或恢复的 lease 可能已经执行了该调用；attempt 保留已确认的值。 */
  public ToolInvocation unknown(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    return withState(ToolInvocationStatus.UNKNOWN, attempt, approval, null, error, now);
  }

  /**
   * 仅替换给定的当前状态字段来复制本行（inputReceipt 原样保留），并在同一处校验转换；identity、frozen call/binding 与 createdAt
   * 通过构造得以保留。
   */
  private ToolInvocation withState(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Instant now) {
    return withState(status, attempt, approval, result, error, inputReceipt, now);
  }

  /**
   * 与 {@link #withState(ToolInvocationStatus, int, ToolApproval, ToolResult, ToolInvocationError,
   * Instant)} 相同，但显式指定 inputReceipt（仅接受回答时写入，其余转换原样保留）。
   */
  private ToolInvocation withState(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolInputReceipt inputReceipt,
      Instant now) {
    ToolInvocation next =
        new ToolInvocation(
            id,
            threadId,
            turnStartEntryId,
            assistantEntryId,
            callIndex,
            call,
            binding,
            status,
            attempt,
            approval,
            result,
            error,
            createdAt,
            now,
            inputReceipt);
    validateTransition(this, next);
    return next;
  }

  private boolean hasCompletedPreflightApproval() {
    return approval != null
        && (!approval.required() || approval.decision() == ToolApprovalDecision.ALLOWED);
  }
}
