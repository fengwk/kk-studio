package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable current state of one Tool invocation.
 *
 * <p>{@code attempt} counts executions actually accepted by the execution side; BUSY/OVERLOADED or
 * pre-execution local rejections do not increase it. {@code resultEntryId} links the ToolResult
 * Entry and is only present (possibly) on terminal states. Result and error are mutually exclusive
 * on every status; non-terminal states never carry terminal facts.
 *
 * <p>{@code DISPATCHING} means the Work lease is held and Gateway admission is in flight: whether
 * the external side accepted the execution is not yet durably confirmed. All state changes go
 * through the pure transition methods below; the Store must run {@link #validateTransition} before
 * every {@code update*} write so direct record construction stays limited to persistence decode.
 */
public record ToolInvocation(
    long id,
    long modelInvocationId,
    long assistantEntryId,
    int ordinal,
    ToolInvocationRequest request,
    ToolInvocationStatus status,
    int attempt,
    ToolApproval approval,
    ToolResult result,
    ToolInvocationError error,
    Long resultEntryId,
    Instant createdAt,
    Instant updatedAt) {

  public ToolInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("invocation id must be positive");
    }
    if (modelInvocationId <= 0) {
      throw new IllegalArgumentException("modelInvocationId must be positive");
    }
    if (assistantEntryId <= 0) {
      throw new IllegalArgumentException("assistantEntryId must be positive");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    validateStatusFields(status, attempt, approval, result, error, resultEntryId, request);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * Validates that {@code next} is a legal transition of the stored {@code stored} row: identity
   * and request are immutable, updatedAt never regresses, attempt only advances by exactly one on a
   * confirmed start (DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN), an approval may only be
   * introduced as {@code not-required} on READY-&gt;READY or as a required undecided request on
   * READY-&gt;WAITING_APPROVAL, an undecided approval may only be decided (ALLOWED -&gt; READY,
   * DENIED -&gt; FAILED) or replayed exactly, decided / non-required approvals are immutable, and
   * terminal facts are immutable (only {@code resultEntryId} may attach from null to positive).
   * Exact replay is always accepted.
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
    if (stored.id() != next.id()
        || stored.modelInvocationId() != next.modelInvocationId()
        || stored.assistantEntryId() != next.assistantEntryId()
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
   * An approval may only be introduced from a null stored approval as {@code not-required} on
   * READY-&gt;READY or as a required undecided request on READY-&gt;WAITING_APPROVAL; direct-record
   * injection of a decided approval is rejected. An undecided approval may only be decided (with
   * the stored request time preserved) or replayed exactly, and a decision must match its status:
   * ALLOWED resumes as READY, DENIED terminates as FAILED. Decided and non-required approvals are
   * immutable; a WAITING_APPROVAL row cancelled while keeping the exact undecided approval stays
   * legal (Stop).
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
        || !Objects.equals(stored.error(), next.error())) {
      throw new IllegalArgumentException("terminal tool invocation facts must not change");
    }
    Long storedResultEntryId = stored.resultEntryId();
    Long nextResultEntryId = next.resultEntryId();
    if (storedResultEntryId == null) {
      if (nextResultEntryId != null && nextResultEntryId <= 0) {
        throw new IllegalArgumentException("terminal resultEntryId must be positive");
      }
    } else if (!storedResultEntryId.equals(nextResultEntryId)) {
      throw new IllegalArgumentException("terminal resultEntryId must not change");
    }
  }

  /**
   * READY with no approval -&gt; READY with a {@code not-required} approval; later decisions are
   * impossible, so dispatch needs no approval round trip.
   */
  public ToolInvocation markApprovalNotRequired(Instant now) {
    if (approval != null) {
      throw new IllegalArgumentException("approval already exists");
    }
    return withState(
        ToolInvocationStatus.READY, attempt, ToolApproval.notRequired(), null, null, null, now);
  }

  /** READY with no approval -&gt; WAITING_APPROVAL with a required undecided approval. */
  public ToolInvocation requestApproval(String reason, Instant now) {
    if (approval != null) {
      throw new IllegalArgumentException("approval already exists");
    }
    return withState(
        ToolInvocationStatus.WAITING_APPROVAL,
        attempt,
        ToolApproval.request(requireNow(now), reason),
        null,
        null,
        null,
        now);
  }

  /**
   * Applies an approval decision: ALLOWED resumes the invocation as READY, DENIED terminates it as
   * FAILED. The same decisionId with the exact same decision payload is idempotent; the same id
   * with a different payload or an existing different decision is a conflict.
   */
  public ToolInvocation decideApproval(
      ToolApprovalDecision decision,
      String decisionId,
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
      return withState(ToolInvocationStatus.READY, attempt, nextApproval, null, null, null, now);
    }
    return withState(
        ToolInvocationStatus.FAILED,
        attempt,
        nextApproval,
        null,
        new ToolInvocationError("DENIED", reason == null ? "denied by user" : reason),
        null,
        now);
  }

  /**
   * READY -&gt; DISPATCHING: the Work lease is held and Gateway admission starts; requires the
   * preflight approval to be completed (non-null and either not required or ALLOWED); attempt
   * unchanged.
   */
  public ToolInvocation beginDispatch(Instant now) {
    if (!hasCompletedPreflightApproval()) {
      throw new IllegalArgumentException("beginDispatch requires a completed preflight approval");
    }
    return withState(ToolInvocationStatus.DISPATCHING, attempt, approval, null, null, null, now);
  }

  /**
   * DISPATCHING -&gt; FAILED: the Gateway definitely rejected before any execution; attempt
   * unchanged. Only a dispatch in flight can be rejected, and {@link #fail} refuses DISPATCHING, so
   * this is the only path that fails a dispatch.
   */
  public ToolInvocation rejectDispatch(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("rejectDispatch requires DISPATCHING status");
    }
    return withState(ToolInvocationStatus.FAILED, attempt, approval, null, error, null, now);
  }

  /**
   * DISPATCHING -&gt; READY: BUSY/OVERLOADED admission; attempt unchanged. Only a dispatch in
   * flight can bounce back to READY.
   */
  public ToolInvocation dispatchBusy(Instant now) {
    if (status != ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("dispatchBusy requires DISPATCHING status");
    }
    return withState(ToolInvocationStatus.READY, attempt, approval, null, null, null, now);
  }

  /**
   * DISPATCHING -&gt; RUNNING: the Gateway confirmed the start; attempt advances by exactly one.
   */
  public ToolInvocation markRunning(Instant now) {
    return withState(
        ToolInvocationStatus.RUNNING, Math.addExact(attempt, 1), approval, null, null, null, now);
  }

  /**
   * RUNNING -&gt; READY: the execution side reported a retryable failure, so the same attempt is
   * re-scheduled from scratch; attempt and the completed preflight approval stay confirmed.
   */
  public ToolInvocation retryReady(Instant now) {
    if (status != ToolInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    return withState(ToolInvocationStatus.READY, attempt, approval, null, null, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED with the complete ToolResult; attempt must be positive. */
  public ToolInvocation succeed(ToolResult result, Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(ToolInvocationStatus.SUCCEEDED, attempt, approval, result, null, null, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED with a terminal error; attempt unchanged. WAITING_APPROVAL and
   * DISPATCHING are refused: an undecided approval may only be denied through a decided DENIED
   * decision or stopped as CANCELLED, and a dispatch in flight may only be failed through {@link
   * #rejectDispatch}.
   */
  public ToolInvocation fail(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ToolInvocationStatus.READY && status != ToolInvocationStatus.RUNNING) {
      throw new IllegalArgumentException(
          "fail requires READY or RUNNING status; WAITING_APPROVAL must be decided or stopped,"
              + " and a DISPATCHING invocation must use rejectDispatch");
    }
    return withState(ToolInvocationStatus.FAILED, attempt, approval, null, error, null, now);
  }

  /**
   * WAITING_APPROVAL / READY / RUNNING -&gt; CANCELLED with a terminal error; attempt unchanged.
   * DISPATCHING is deliberately rejected: in that window the execution may already have started, so
   * the only honest termination is UNKNOWN.
   */
  public ToolInvocation cancel(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status == ToolInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException(
          "a DISPATCHING tool invocation must terminate as UNKNOWN, not CANCELLED");
    }
    return withState(ToolInvocationStatus.CANCELLED, attempt, approval, null, error, null, now);
  }

  /**
   * DISPATCHING / RUNNING -&gt; UNKNOWN with a terminal error: indeterminate admission or recovered
   * lease may have executed the call, so DISPATCHING advances attempt by exactly one while RUNNING
   * keeps its already-confirmed attempt.
   */
  public ToolInvocation unknown(ToolInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ToolInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(ToolInvocationStatus.UNKNOWN, nextAttempt, approval, null, error, null, now);
  }

  /**
   * Terminal -&gt; same terminal linking the ToolResult Entry; every other terminal fact stays
   * immutable.
   */
  public ToolInvocation attachResultEntry(long resultEntryId, Instant now) {
    return withState(status, attempt, approval, result, error, resultEntryId, now);
  }

  /**
   * Copies this row with only the given current-state fields replaced and validates the transition
   * in one place; identity, frozen request and createdAt are preserved by construction.
   */
  private ToolInvocation withState(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Long resultEntryId,
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
            error,
            resultEntryId,
            createdAt,
            requireNow(now));
    validateTransition(this, next);
    return next;
  }

  private boolean hasCompletedPreflightApproval() {
    return approval != null
        && (!approval.required() || approval.decision() == ToolApprovalDecision.ALLOWED);
  }

  private static Instant requireNow(Instant now) {
    return Objects.requireNonNull(now, "now");
  }

  private static void validateStatusFields(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      Long resultEntryId,
      ToolInvocationRequest request) {
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (terminal && resultEntryId != null && resultEntryId <= 0) {
      throw new IllegalArgumentException("terminal resultEntryId must be positive");
    }
    if (status == ToolInvocationStatus.WAITING_APPROVAL) {
      if (approval == null || !approval.required() || !approval.isUndecided()) {
        throw new IllegalArgumentException(
            "WAITING_APPROVAL requires a required undecided approval");
      }
      if (attempt != 0) {
        throw new IllegalArgumentException("WAITING_APPROVAL requires attempt 0");
      }
      requireNoTerminalFacts(status, result, error);
    } else if (status == ToolInvocationStatus.READY) {
      requireNoTerminalFacts(status, result, error);
      if (approval != null
          && (approval.isUndecided() || approval.decision() == ToolApprovalDecision.DENIED)) {
        throw new IllegalArgumentException("READY must not carry an undecided or denied approval");
      }
    } else if (status == ToolInvocationStatus.DISPATCHING) {
      requireNoTerminalFacts(status, result, error);
      requireCompletedPreflightApproval(status, approval);
    } else if (status == ToolInvocationStatus.RUNNING) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("RUNNING requires a positive attempt");
      }
      requireNoTerminalFacts(status, result, error);
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
    } else {
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
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
      ToolInvocationStatus status, ToolResult result, ToolInvocationError error) {
    if (result != null || error != null) {
      throw new IllegalArgumentException(status + " must not carry terminal facts");
    }
  }
}
