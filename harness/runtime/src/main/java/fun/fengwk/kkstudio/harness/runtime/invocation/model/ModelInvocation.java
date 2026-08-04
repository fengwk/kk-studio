package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable current state of one Model invocation.
 *
 * <p>{@code attempt} counts confirmed Provider call starts; BUSY/OVERLOADED or definite pre-start
 * rejections do not increase it. {@code resultEntryId} links the execution result to the Session
 * history and is only present (possibly) on terminal states. {@code streamCheckpoint} is the safe
 * partial of the current attempt and is never a second result.
 *
 * <p>{@code DISPATCHING} means the Work lease is held and Gateway admission is in flight: whether
 * the external side accepted the call is not yet durably confirmed. All state changes go through
 * the pure transition methods below; the Store must run {@link #validateTransition} before every
 * {@code update*} write so direct record construction stays limited to persistence decode.
 */
public record ModelInvocation(
    long id,
    long threadId,
    long turnStartEntryId,
    long basisHeadEntryId,
    ModelInvocationRequest request,
    ModelInvocationStatus status,
    int attempt,
    StreamCheckpoint streamCheckpoint,
    ProviderResponse result,
    ModelInvocationError error,
    Long resultEntryId,
    Instant createdAt,
    Instant updatedAt) {

  public ModelInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("invocation id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    if (basisHeadEntryId <= 0) {
      throw new IllegalArgumentException("basisHeadEntryId must be positive");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    if (streamCheckpoint != null && streamCheckpoint.attempt() != attempt) {
      throw new IllegalArgumentException("streamCheckpoint attempt must match invocation attempt");
    }
    validateStatusFields(status, attempt, result, error, resultEntryId, streamCheckpoint);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * Validates that {@code next} is a legal transition of the stored {@code stored} row: identity
   * and request are immutable, updatedAt never regresses, attempt advances by exactly one only on
   * DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN (confirmed start) or
   * DISPATCHING-&gt;CANCELLED (Stop window where the call may already have started); terminal facts
   * are immutable (only {@code resultEntryId} may attach from null to positive); a checkpoint may
   * only be introduced or grown on RUNNING-&gt;RUNNING, and any transition away from RUNNING or
   * inside a terminal may only keep the exact stored checkpoint or clear it. Exact replay is always
   * accepted.
   */
  public static void validateTransition(ModelInvocation stored, ModelInvocation next) {
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
    if (stored.status().isTerminal()) {
      requireTerminalImmutability(stored, next);
    }
    requireCheckpointTransition(stored, next);
  }

  private static void requireStableIdentity(ModelInvocation stored, ModelInvocation next) {
    if (stored.id() != next.id()
        || stored.threadId() != next.threadId()
        || stored.turnStartEntryId() != next.turnStartEntryId()
        || stored.basisHeadEntryId() != next.basisHeadEntryId()
        || !stored.request().equals(next.request())
        || !stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException(
          "model invocation identity"
              + " (id/thread/turnStartEntry/basisHeadEntry/request/createdAt) must not change");
    }
  }

  private static void requireLegalStatusMove(
      ModelInvocationStatus stored, ModelInvocationStatus next) {
    boolean allowed =
        switch (stored) {
          case READY -> next == ModelInvocationStatus.READY
              || next == ModelInvocationStatus.DISPATCHING
              || next == ModelInvocationStatus.FAILED
              || next == ModelInvocationStatus.CANCELLED;
          case DISPATCHING -> next == ModelInvocationStatus.READY
              || next == ModelInvocationStatus.RUNNING
              || next == ModelInvocationStatus.FAILED
              || next == ModelInvocationStatus.CANCELLED
              || next == ModelInvocationStatus.UNKNOWN;
          case RUNNING -> next == ModelInvocationStatus.RUNNING
              || next == ModelInvocationStatus.READY
              || next == ModelInvocationStatus.SUCCEEDED
              || next == ModelInvocationStatus.FAILED
              || next == ModelInvocationStatus.CANCELLED
              || next == ModelInvocationStatus.UNKNOWN;
          case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> next == stored;
        };
    if (!allowed) {
      throw new IllegalArgumentException(
          "illegal model invocation status transition " + stored + " -> " + next);
    }
  }

  private static void requireAttemptDelta(
      ModelInvocation stored, ModelInvocation next, int attemptDelta) {
    boolean advancesAttempt =
        stored.status() == ModelInvocationStatus.DISPATCHING
            && (next.status() == ModelInvocationStatus.RUNNING
                || next.status() == ModelInvocationStatus.UNKNOWN
                || next.status() == ModelInvocationStatus.CANCELLED);
    if (advancesAttempt) {
      if (attemptDelta != 1) {
        throw new IllegalArgumentException(
            "a confirmed start or the DISPATCHING stop window must advance attempt by exactly"
                + " one");
      }
    } else if (attemptDelta != 0) {
      throw new IllegalArgumentException("attempt must not change on this transition");
    }
  }

  private static void requireTerminalImmutability(ModelInvocation stored, ModelInvocation next) {
    if (stored.status() != next.status()
        || stored.attempt() != next.attempt()
        || !Objects.equals(stored.result(), next.result())
        || !Objects.equals(stored.error(), next.error())) {
      throw new IllegalArgumentException("terminal model invocation facts must not change");
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
   * A checkpoint may only be introduced or grown on a RUNNING-&gt;RUNNING transition. Entering a
   * terminal state or retrying as READY may keep the exact stored checkpoint or clear it; inside a
   * terminal no checkpoint may be introduced, grown or forked.
   */
  private static void requireCheckpointTransition(ModelInvocation stored, ModelInvocation next) {
    StreamCheckpoint storedCheckpoint = stored.streamCheckpoint();
    StreamCheckpoint nextCheckpoint = next.streamCheckpoint();
    boolean runningToRunning =
        stored.status() == ModelInvocationStatus.RUNNING
            && next.status() == ModelInvocationStatus.RUNNING;
    if (storedCheckpoint == null) {
      if (nextCheckpoint != null && !runningToRunning) {
        throw new IllegalArgumentException(
            "a checkpoint may only be introduced on a RUNNING -> RUNNING transition");
      }
      return;
    }
    if (nextCheckpoint == null) {
      boolean clearingAllowed =
          next.status().isTerminal() || next.status() == ModelInvocationStatus.READY;
      if (!clearingAllowed) {
        throw new IllegalArgumentException(
            "checkpoint may only be cleared when entering a terminal state or retrying as READY");
      }
      return;
    }
    if (!runningToRunning) {
      if (!nextCheckpoint.equals(storedCheckpoint)) {
        throw new IllegalArgumentException(
            "a transition away from RUNNING may only keep the exact stored checkpoint or clear"
                + " it");
      }
      return;
    }
    if (nextCheckpoint.attempt() != storedCheckpoint.attempt()) {
      throw new IllegalArgumentException("checkpoint must stay on the same attempt");
    }
    if (nextCheckpoint.sequence() < storedCheckpoint.sequence()) {
      throw new IllegalArgumentException("checkpoint sequence must not regress");
    }
    if (nextCheckpoint.sequence() == storedCheckpoint.sequence()) {
      if (!nextCheckpoint.equals(storedCheckpoint)) {
        throw new IllegalArgumentException("same checkpoint sequence requires exact replay");
      }
      return;
    }
    if (!isPrefix(storedCheckpoint.text(), nextCheckpoint.text())
        || !isPrefix(storedCheckpoint.thinking(), nextCheckpoint.thinking())) {
      throw new IllegalArgumentException(
          "a larger checkpoint sequence must keep text and thinking as strict prefixes");
    }
  }

  private static boolean isPrefix(String prefix, String value) {
    String actualPrefix = prefix == null ? "" : prefix;
    String actualValue = value == null ? "" : value;
    return actualValue.startsWith(actualPrefix);
  }

  /**
   * READY -&gt; DISPATCHING: the Work lease is held and Gateway admission starts; attempt
   * unchanged.
   */
  public ModelInvocation beginDispatch(Instant now) {
    return withState(ModelInvocationStatus.DISPATCHING, attempt, null, null, null, null, now);
  }

  /**
   * DISPATCHING -&gt; FAILED: the Gateway definitely rejected before any Provider start; attempt
   * unchanged. Only a dispatch in flight can be rejected, and {@link #fail} refuses DISPATCHING, so
   * this is the only path that fails a dispatch.
   */
  public ModelInvocation rejectDispatch(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("rejectDispatch requires DISPATCHING status");
    }
    return withState(ModelInvocationStatus.FAILED, attempt, null, null, error, null, now);
  }

  /**
   * DISPATCHING -&gt; READY: BUSY/OVERLOADED admission; attempt unchanged. Only a dispatch in
   * flight can bounce back to READY.
   */
  public ModelInvocation dispatchBusy(Instant now) {
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("dispatchBusy requires DISPATCHING status");
    }
    return withState(ModelInvocationStatus.READY, attempt, null, null, null, null, now);
  }

  /**
   * DISPATCHING -&gt; RUNNING: the Gateway confirmed the start; attempt advances by exactly one.
   */
  public ModelInvocation markRunning(Instant now) {
    return withState(
        ModelInvocationStatus.RUNNING, Math.addExact(attempt, 1), null, null, null, null, now);
  }

  /**
   * RUNNING -&gt; READY: the execution side reported a retryable failure, so the same attempt is
   * re-scheduled from scratch; attempt stays confirmed and the safe checkpoint is dropped.
   */
  public ModelInvocation retryReady(Instant now) {
    if (status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    return withState(ModelInvocationStatus.READY, attempt, null, null, null, null, now);
  }

  /**
   * RUNNING -&gt; RUNNING with a monotonic safe text/thinking checkpoint of the current attempt.
   */
  public ModelInvocation checkpoint(StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    return withState(ModelInvocationStatus.RUNNING, attempt, checkpoint, null, null, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED with the complete Provider result; attempt must be positive. */
  public ModelInvocation succeed(ProviderResponse result, Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(
        ModelInvocationStatus.SUCCEEDED, attempt, streamCheckpoint, result, null, null, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED with a terminal error; attempt unchanged. DISPATCHING is refused:
   * a dispatch in flight may only be failed through {@link #rejectDispatch}.
   */
  public ModelInvocation fail(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException(
          "fail requires READY or RUNNING status; a DISPATCHING invocation must use"
              + " rejectDispatch");
    }
    return withState(ModelInvocationStatus.FAILED, attempt, null, null, error, null, now);
  }

  /**
   * READY / DISPATCHING / RUNNING -&gt; CANCELLED with a terminal error. READY and RUNNING keep
   * their confirmed attempt; DISPATCHING is the Stop window where the call may already have
   * started, so attempt advances by exactly one.
   */
  public ModelInvocation cancel(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(ModelInvocationStatus.CANCELLED, nextAttempt, null, null, error, null, now);
  }

  /**
   * DISPATCHING / RUNNING -&gt; UNKNOWN with a terminal error: indeterminate admission or recovered
   * lease may have started the call, so DISPATCHING advances attempt by exactly one while RUNNING
   * keeps its already-confirmed attempt.
   */
  public ModelInvocation unknown(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(ModelInvocationStatus.UNKNOWN, nextAttempt, null, null, error, null, now);
  }

  /**
   * Terminal -&gt; same terminal linking the execution result Entry; the safe checkpoint may be
   * cleared while every other terminal fact stays immutable.
   */
  public ModelInvocation attachResultEntry(long resultEntryId, Instant now) {
    return withState(status, attempt, null, result, error, resultEntryId, now);
  }

  /**
   * Copies this row with only the given current-state fields replaced and validates the transition
   * in one place; identity, frozen request and createdAt are preserved by construction.
   */
  private ModelInvocation withState(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      Long resultEntryId,
      Instant now) {
    ModelInvocation next =
        new ModelInvocation(
            id,
            threadId,
            turnStartEntryId,
            basisHeadEntryId,
            request,
            status,
            attempt,
            streamCheckpoint,
            result,
            error,
            resultEntryId,
            createdAt,
            requireNow(now));
    validateTransition(this, next);
    return next;
  }

  private static Instant requireNow(Instant now) {
    return Objects.requireNonNull(now, "now");
  }

  private static void validateStatusFields(
      ModelInvocationStatus status,
      int attempt,
      ProviderResponse result,
      ModelInvocationError error,
      Long resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (terminal && resultEntryId != null && resultEntryId <= 0) {
      throw new IllegalArgumentException("terminal resultEntryId must be positive");
    }
    if (status == ModelInvocationStatus.READY) {
      if (streamCheckpoint != null) {
        throw new IllegalArgumentException("READY must not carry a stream checkpoint");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("READY must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.DISPATCHING) {
      if (streamCheckpoint != null) {
        throw new IllegalArgumentException("DISPATCHING must not carry a stream checkpoint");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("DISPATCHING must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.RUNNING) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("RUNNING requires a positive attempt");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("RUNNING must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.SUCCEEDED) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      if (result == null) {
        throw new IllegalArgumentException("SUCCEEDED requires a result");
      }
      if (error != null) {
        throw new IllegalArgumentException("SUCCEEDED must not carry an error");
      }
    } else if (status == ModelInvocationStatus.FAILED) {
      if (error == null) {
        throw new IllegalArgumentException("FAILED requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("FAILED must not carry a result");
      }
    } else if (status == ModelInvocationStatus.UNKNOWN) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("UNKNOWN requires a positive attempt");
      }
      if (error == null) {
        throw new IllegalArgumentException("UNKNOWN requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("UNKNOWN must not carry a result");
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
}
