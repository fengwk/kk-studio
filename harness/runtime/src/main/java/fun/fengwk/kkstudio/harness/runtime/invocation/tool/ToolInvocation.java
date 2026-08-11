package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Tool invocation 的 durable 当前状态。
 *
 * <p>{@code attempt} 统计执行端实际接受的执行次数；BUSY/OVERLOADED 或执行前的本地拒绝不会增加它。 {@code resultEntryId} 链接
 * ToolResult Entry，且仅（在某些情况下）出现于 terminal 状态。result 与 error 在任何状态上都互斥；非 terminal 状态永远不携带 terminal
 * 事实。
 *
 * <p>{@code DISPATCHING} 表示 Work lease 已持有，Gateway admission 进行中：外部服务是否接受执行尚未被 durable
 * 确认。纯转换会把回拨的调用方 wall-clock 抬升到当前 {@code updatedAt}；Store 仍必须在每次 {@code update*} 写入前调用 {@link
 * #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ToolInvocation(
    UUID id,
    UUID modelInvocationId,
    UUID assistantEntryId,
    int ordinal,
    ToolInvocationRequest request,
    ToolInvocationStatus status,
    int attempt,
    ToolApproval approval,
    ToolResult result,
    ToolEffectBatch effects,
    ToolInvocationError error,
    UUID resultEntryId,
    Instant createdAt,
    Instant updatedAt) {

  /** 构造不携带 branch effects 的 invocation；普通 Tool 与非成功状态使用此便捷入口。 */
  public ToolInvocation(
      UUID id,
      UUID modelInvocationId,
      UUID assistantEntryId,
      int ordinal,
      ToolInvocationRequest request,
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      UUID resultEntryId,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        modelInvocationId,
        assistantEntryId,
        ordinal,
        request,
        status,
        attempt,
        approval,
        result,
        ToolEffectBatch.EMPTY,
        error,
        resultEntryId,
        createdAt,
        updatedAt);
  }

  public ToolInvocation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(modelInvocationId, "modelInvocationId");
    Objects.requireNonNull(assistantEntryId, "assistantEntryId");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    effects = Objects.requireNonNull(effects, "effects");
    validateStatusFields(status, attempt, approval, result, effects, error, resultEntryId, request);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 request 不可变，updatedAt 不允许回退， attempt
   * 仅在已确认启动（DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN）时恰好 +1；approval 仅能以 {@code
   * not-required} 引入到 READY-&gt;READY，或以 required undecided request 引入到
   * READY-&gt;WAITING_APPROVAL；undecided approval 仅能被决定（ALLOWED -&gt; READY，DENIED -&gt; FAILED）
   * 或精确 replay；已决定 / non-required approval 不可变；terminal 事实不可变（仅 {@code resultEntryId} 可从 null
   * 附加为正数）。精确 replay 始终被接受。
   */
  public static void validateTransition(ToolInvocation stored, ToolInvocation next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    requireStableIdentity(stored, next);
    if (next.updatedAt().isBefore(stored.updatedAt())) {
      throw new IllegalArgumentException("updatedAt must not regress");
    }
    int attemptDelta = next.attempt() - stored.attempt();
    if (attemptDelta < 0) {
      throw new IllegalArgumentException("attempt must not regress");
    }
    requireLegalStatusMove(stored.status(), next.status());
    requireAttemptDelta(stored, next, attemptDelta);
    requireApprovalRules(stored, next);
    if (stored.status().isTerminal()) {
      requireTerminalImmutability(stored, next);
    }
  }

  private static void requireStableIdentity(ToolInvocation stored, ToolInvocation next) {
    if (!stored.id().equals(next.id())
        || !stored.modelInvocationId().equals(next.modelInvocationId())
        || !stored.assistantEntryId().equals(next.assistantEntryId())
        || stored.ordinal() != next.ordinal()
        || !stored.request().equals(next.request())
        || !stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException(
          "tool invocation identity"
              + " (id/modelInvocation/assistantEntry/ordinal/request/createdAt) must not change");
    }
  }

  private static void requireLegalStatusMove(
      ToolInvocationStatus stored, ToolInvocationStatus next) {
    boolean allowed =
        switch (stored) {
          case WAITING_APPROVAL -> next == ToolInvocationStatus.WAITING_APPROVAL
              || next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED;
          case READY -> next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.WAITING_APPROVAL
              || next == ToolInvocationStatus.DISPATCHING
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED;
          case DISPATCHING -> next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.RUNNING
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.UNKNOWN;
          case RUNNING -> next == ToolInvocationStatus.RUNNING
              || next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.SUCCEEDED
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED
              || next == ToolInvocationStatus.UNKNOWN;
          case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> next == stored;
        };
    if (!allowed) {
      throw new IllegalArgumentException(
          "illegal tool invocation status transition " + stored + " -> " + next);
    }
  }

  private static void requireAttemptDelta(
      ToolInvocation stored, ToolInvocation next, int attemptDelta) {
    boolean confirmedStart =
        stored.status() == ToolInvocationStatus.DISPATCHING
            && (next.status() == ToolInvocationStatus.RUNNING
                || next.status() == ToolInvocationStatus.UNKNOWN);
    if (confirmedStart) {
      if (attemptDelta != 1) {
        throw new IllegalArgumentException("a confirmed start must advance attempt by exactly one");
      }
    } else if (attemptDelta != 0) {
      throw new IllegalArgumentException("attempt must not change on this transition");
    }
  }

  /**
   * approval 仅能从 null stored approval 出发，以 {@code not-required} 引入到 READY-&gt;READY，或以 required
   * undecided request 引入到 READY-&gt;WAITING_APPROVAL；直接通过 record 注入已决定的 approval 会被拒绝。undecided
   * approval 仅能被决定（保留 stored request time）或精确 replay，决策 必须与状态匹配：ALLOWED 恢复为 READY，DENIED 终止为
   * FAILED。已决定与 non-required approval 不可变； WAITING_APPROVAL 行在保留完全相同的 undecided approval
   * 同时被取消（Stop）保持合法。
   */
  private static void requireApprovalRules(ToolInvocation stored, ToolInvocation next) {
    ToolApproval storedApproval = stored.approval();
    ToolApproval nextApproval = next.approval();
    if (storedApproval == null) {
      if (nextApproval == null) {
        return;
      }
      boolean introAsNotRequired =
          stored.status() == ToolInvocationStatus.READY
              && next.status() == ToolInvocationStatus.READY
              && !nextApproval.required();
      boolean introAsRequest =
          stored.status() == ToolInvocationStatus.READY
              && next.status() == ToolInvocationStatus.WAITING_APPROVAL
              && nextApproval.required()
              && nextApproval.isUndecided();
      if (!introAsNotRequired && !introAsRequest) {
        throw new IllegalArgumentException(
            "approval may only be introduced as not-required on READY -> READY or as a required"
                + " undecided request on READY -> WAITING_APPROVAL");
      }
      return;
    }
    if (storedApproval.isUndecided()) {
      if (nextApproval != null
          && nextApproval.required()
          && nextApproval.decision() != null
          && nextApproval.requestedAt().equals(storedApproval.requestedAt())) {
        if (nextApproval.decision() == ToolApprovalDecision.ALLOWED
            && next.status() != ToolInvocationStatus.READY) {
          throw new IllegalArgumentException(
              "an ALLOWED decision must resume the invocation as READY");
        }
        if (nextApproval.decision() == ToolApprovalDecision.DENIED
            && next.status() != ToolInvocationStatus.FAILED) {
          throw new IllegalArgumentException(
              "a DENIED decision must terminate the invocation as FAILED");
        }
        return;
      }
      if (!storedApproval.equals(nextApproval)) {
        throw new IllegalArgumentException(
            "an undecided approval may only be decided or replayed exactly");
      }
      // 保留 exact undecided approval 只允许保持 WAITING_APPROVAL 或 Stop 为 CANCELLED；
      // 不能直接 FAILED（denied 必须走 decided decision 路径）。
      if (next.status() != ToolInvocationStatus.WAITING_APPROVAL
          && next.status() != ToolInvocationStatus.CANCELLED) {
        throw new IllegalArgumentException(
            "an undecided approval may only keep WAITING_APPROVAL or be stopped as CANCELLED");
      }
      return;
    }
    if (!storedApproval.equals(nextApproval)) {
      throw new IllegalArgumentException("a decided or non-required approval must not change");
    }
  }

  private static void requireTerminalImmutability(ToolInvocation stored, ToolInvocation next) {
    if (stored.status() != next.status()
        || stored.attempt() != next.attempt()
        || !Objects.equals(stored.result(), next.result())
        || !stored.effects().equals(next.effects())
        || !Objects.equals(stored.error(), next.error())) {
      throw new IllegalArgumentException("terminal tool invocation facts must not change");
    }
    UUID storedResultEntryId = stored.resultEntryId();
    UUID nextResultEntryId = next.resultEntryId();
    if (storedResultEntryId == null) {
      return;
    }
    if (!Objects.equals(storedResultEntryId, nextResultEntryId)) {
      throw new IllegalArgumentException("terminal resultEntryId must not change");
    }
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
        ToolInvocationStatus.READY,
        attempt,
        ToolApproval.notRequired(),
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        now);
  }

  /** READY 且无 approval -&gt; WAITING_APPROVAL，并附带一个 required undecided approval。 */
  public ToolInvocation requestApproval(String reason, Instant now) {
    if (approval != null) {
      throw new IllegalArgumentException("approval already exists");
    }
    Instant effectiveNow = effectiveMutationTime(now);
    return withState(
        ToolInvocationStatus.WAITING_APPROVAL,
        attempt,
        ToolApproval.request(effectiveNow, reason),
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        effectiveNow);
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
      return withState(
          ToolInvocationStatus.READY,
          attempt,
          nextApproval,
          null,
          ToolEffectBatch.EMPTY,
          null,
          null,
          now);
    }
    return withState(
        ToolInvocationStatus.FAILED,
        attempt,
        nextApproval,
        null,
        ToolEffectBatch.EMPTY,
        new ToolInvocationError("DENIED", reason == null ? "denied by user" : reason),
        null,
        now);
  }

  /**
   * READY -&gt; DISPATCHING：Work lease 已持有，Gateway admission 启动；要求预检 approval 已完成 （非 null 且为
   * not-required 或 ALLOWED）；attempt 不变。
   */
  public ToolInvocation beginDispatch(Instant now) {
    if (!hasCompletedPreflightApproval()) {
      throw new IllegalArgumentException("beginDispatch requires a completed preflight approval");
    }
    return withState(
        ToolInvocationStatus.DISPATCHING,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        now);
  }

  /**
   * DISPATCHING -&gt; FAILED：Gateway 在任何执行之前明确拒绝了该调用；attempt 不变。仅进行中的 dispatch 能被拒绝，且 {@link #fail}
   * 拒绝 DISPATCHING，所以这是失败 dispatch 的唯一路径。
   */
  public ToolInvocation rejectDispatch(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("rejectDispatch requires DISPATCHING status");
    }
    return withState(
        ToolInvocationStatus.FAILED,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        error,
        null,
        now);
  }

  /** DISPATCHING -&gt; READY：BUSY/OVERLOADED admission；attempt 不变。仅进行中的 dispatch 能被弹回 READY。 */
  public ToolInvocation dispatchBusy(Instant now) {
    if (status != ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("dispatchBusy requires DISPATCHING status");
    }
    return withState(
        ToolInvocationStatus.READY,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        now);
  }

  /** DISPATCHING -&gt; RUNNING：Gateway 已确认启动；attempt 恰好 +1。 */
  public ToolInvocation markRunning(Instant now) {
    return withState(
        ToolInvocationStatus.RUNNING,
        Math.addExact(attempt, 1),
        approval,
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        now);
  }

  /** RUNNING -&gt; READY：执行端报告了可重试的失败，因此同一个 attempt 被从头重新调度；attempt 与 已完成的预检 approval 保持已确认。 */
  public ToolInvocation retryReady(Instant now) {
    if (status != ToolInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    return withState(
        ToolInvocationStatus.READY,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        null,
        null,
        now);
  }

  /** RUNNING -&gt; SUCCEEDED，并附带完整的 ToolResult；attempt 必须为正数。 */
  public ToolInvocation succeed(ToolResult result, ToolEffectBatch effects, Instant now) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(effects, "effects");
    return withState(
        ToolInvocationStatus.SUCCEEDED, attempt, approval, result, effects, null, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED，且不产生 branch effects。 */
  public ToolInvocation succeed(ToolResult result, Instant now) {
    return succeed(result, ToolEffectBatch.EMPTY, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED，并附带一个 terminal error；attempt 不变。WAITING_APPROVAL 与 DISPATCHING
   * 被拒绝：undecided approval 只能通过已决定的 DENIED 决策来拒绝，或被作为 CANCELLED 终止；进行中的 dispatch 只能通过 {@link
   * #rejectDispatch} 失败。
   */
  public ToolInvocation fail(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ToolInvocationStatus.READY && status != ToolInvocationStatus.RUNNING) {
      throw new IllegalArgumentException(
          "fail requires READY or RUNNING status; WAITING_APPROVAL must be decided or stopped,"
              + " and a DISPATCHING invocation must use rejectDispatch");
    }
    return withState(
        ToolInvocationStatus.FAILED,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        error,
        null,
        now);
  }

  /**
   * WAITING_APPROVAL / READY / RUNNING -&gt; CANCELLED，并附带一个 terminal error；attempt 不变。 DISPATCHING
   * 被刻意拒绝：在该窗口内执行可能已经启动，因此唯一诚实的终止是 UNKNOWN。
   */
  public ToolInvocation cancel(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status == ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException(
          "a DISPATCHING tool invocation must terminate as UNKNOWN, not CANCELLED");
    }
    return withState(
        ToolInvocationStatus.CANCELLED,
        attempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        error,
        null,
        now);
  }

  /**
   * DISPATCHING / RUNNING -&gt; UNKNOWN，并附带一个 terminal error：不确定的 admission 或恢复的 lease
   * 可能已经执行了该调用，因此 DISPATCHING 时 attempt 恰好 +1，而 RUNNING 保留其已确认的 attempt。
   */
  public ToolInvocation unknown(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ToolInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(
        ToolInvocationStatus.UNKNOWN,
        nextAttempt,
        approval,
        null,
        ToolEffectBatch.EMPTY,
        error,
        null,
        now);
  }

  /** Terminal -&gt; 同一 terminal 状态，并链接 ToolResult Entry；其他 terminal 事实保持不变。 */
  public ToolInvocation attachResultEntry(UUID resultEntryId, Instant now) {
    return withState(status, attempt, approval, result, effects, error, resultEntryId, now);
  }

  /** 仅替换给定的当前状态字段来复制本行，并在同一处校验转换；identity、frozen request 与 createdAt 通过构造得以保留。 */
  private ToolInvocation withState(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolEffectBatch effects,
      ToolInvocationError error,
      UUID resultEntryId,
      Instant now) {
    ToolInvocation next =
        new ToolInvocation(
            id,
            modelInvocationId,
            assistantEntryId,
            ordinal,
            request,
            status,
            attempt,
            approval,
            result,
            effects,
            error,
            resultEntryId,
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  private boolean hasCompletedPreflightApproval() {
    return approval != null
        && (!approval.required() || approval.decision() == ToolApprovalDecision.ALLOWED);
  }

  private Instant effectiveMutationTime(Instant now) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(updatedAt) ? updatedAt : candidate;
  }

  private static void validateStatusFields(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolEffectBatch effects,
      ToolInvocationError error,
      UUID resultEntryId,
      ToolInvocationRequest request) {
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (status == ToolInvocationStatus.WAITING_APPROVAL) {
      if (approval == null || !approval.required() || !approval.isUndecided()) {
        throw new IllegalArgumentException(
            "WAITING_APPROVAL requires a required undecided approval");
      }
      if (attempt != 0) {
        throw new IllegalArgumentException("WAITING_APPROVAL requires attempt 0");
      }
      requireNoTerminalFacts(status, result, effects, error);
    } else if (status == ToolInvocationStatus.READY) {
      requireNoTerminalFacts(status, result, effects, error);
      if (approval != null
          && (approval.isUndecided() || approval.decision() == ToolApprovalDecision.DENIED)) {
        throw new IllegalArgumentException("READY must not carry an undecided or denied approval");
      }
    } else if (status == ToolInvocationStatus.DISPATCHING) {
      requireNoTerminalFacts(status, result, effects, error);
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.RUNNING) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("RUNNING requires a positive attempt");
      }
      requireNoTerminalFacts(status, result, effects, error);
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.SUCCEEDED) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      if (result == null) {
        throw new IllegalArgumentException("SUCCEEDED requires a result");
      }
      if (!result.toolCallId().equals(request.call().id())) {
        throw new IllegalArgumentException(
            "SUCCEEDED result toolCallId must match the request call");
      }
      if (error != null) {
        throw new IllegalArgumentException("SUCCEEDED must not carry an error");
      }
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.UNKNOWN) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("UNKNOWN requires a positive attempt");
      }
      if (error == null) {
        throw new IllegalArgumentException("UNKNOWN requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("UNKNOWN must not carry a result");
      }
      requireEmptyEffects(status, effects);
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.FAILED) {
      if (approval != null && approval.isUndecided()) {
        throw new IllegalArgumentException("FAILED must not carry an undecided approval");
      }
      if (error == null) {
        throw new IllegalArgumentException("FAILED requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("FAILED must not carry a result");
      }
      requireEmptyEffects(status, effects);
    } else {
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
      requireEmptyEffects(status, effects);
    }
  }

  private static void requireCompletedPreflightApproval(
      ToolInvocationStatus status, ToolApproval approval) {
    if (approval == null
        || (approval.required() && approval.decision() != ToolApprovalDecision.ALLOWED)) {
      throw new IllegalArgumentException(status + " requires a completed preflight approval");
    }
  }

  private static void requireNoTerminalFacts(
      ToolInvocationStatus status,
      ToolResult result,
      ToolEffectBatch effects,
      ToolInvocationError error) {
    if (result != null || error != null || !effects.isEmpty()) {
      throw new IllegalArgumentException(status + " must not carry terminal facts");
    }
  }

  private static void requireEmptyEffects(ToolInvocationStatus status, ToolEffectBatch effects) {
    if (!effects.isEmpty()) {
      throw new IllegalArgumentException(status + " must not carry tool effects");
    }
  }
}
