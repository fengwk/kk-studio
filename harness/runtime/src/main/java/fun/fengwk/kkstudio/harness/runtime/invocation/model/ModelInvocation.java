package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model Invocation 的持久化当前状态。
 *
 * <p>{@code attempt} 统计已确认的 Provider 调用启动次数；BUSY/OVERLOADED 或启动前的明确拒绝不会增加它。 {@code resultEntryId}
 * 将执行结果链接到 Session 历史，且仅（在某些情况下）出现于 terminal 状态。 {@code streamCheckpoint} 是当前 attempt 的安全
 * partial，永远不是第二个 result。{@code failedAttempts} 是 append-only 的瞬态失败 attempt 审计，不属于对话语义。
 *
 * <p>{@code DISPATCHING} 表示 Work lease 已持有，Gateway admission 进行中：外部服务是否接受调用尚未被持久化状态 确认。纯转换会把回拨的调用方
 * wall-clock 抬升到当前 {@code updatedAt}；Store 仍必须在每次 {@code update*} 写入前调用 {@link
 * #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ModelInvocation(
    UUID id,
    UUID threadId,
    UUID turnStartEntryId,
    UUID requestHeadEntryId,
    ModelRequestSpec requestSpec,
    ModelInvocationStatus status,
    int attempt,
    StreamCheckpoint streamCheckpoint,
    ProviderResponse result,
    ModelInvocationError error,
    UUID resultEntryId,
    List<ModelAttemptFailure> failedAttempts,
    Instant createdAt,
    Instant updatedAt) {

  public ModelInvocation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(turnStartEntryId, "turnStartEntryId");
    Objects.requireNonNull(requestHeadEntryId, "requestHeadEntryId");
    requestSpec = Objects.requireNonNull(requestSpec, "requestSpec");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    failedAttempts = List.copyOf(Objects.requireNonNull(failedAttempts, "failedAttempts"));
    for (int index = 0; index < failedAttempts.size(); index++) {
      ModelAttemptFailure failure = failedAttempts.get(index);
      if (failure.attempt() != index + 1) {
        throw new IllegalArgumentException("failedAttempts must contain consecutive attempts 1..N");
      }
      if (failure.attempt() > attempt) {
        throw new IllegalArgumentException("failed attempt must not exceed invocation attempt");
      }
      if (index > 0 && failure.failedAt().isBefore(failedAttempts.get(index - 1).retryAt())) {
        throw new IllegalArgumentException(
            "a failed attempt must not precede the previous retry schedule");
      }
    }
    if (streamCheckpoint != null && streamCheckpoint.attempt() != attempt) {
      throw new IllegalArgumentException("streamCheckpoint attempt must match invocation attempt");
    }
    validateStatusFields(
        status, attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 request 不可变，updatedAt 不允许回退， attempt 仅在
   * DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN（已确认启动）以及 DISPATCHING-&gt;CANCELLED（Stop
   * 窗口内调用可能已开始）这几种情形下恰好 +1；terminal 事实不可变 （仅 {@code resultEntryId} 可从 null 附加为正数）；checkpoint 仅能在
   * RUNNING-&gt;RUNNING 或 RUNNING-&gt;terminal 时被引入或增长；任何离开 RUNNING 或处于 terminal 内的转换，只能保留完全相同的
   * stored checkpoint 或清除它； terminal 结果 Entry 链接时必须同时清空已经物化的 checkpoint / failedAttempts。精确 replay
   * 始终被接受。
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
    requireFailedAttemptsTransition(stored, next);
  }

  private static void requireStableIdentity(ModelInvocation stored, ModelInvocation next) {
    if (!stored.id().equals(next.id())
        || !stored.threadId().equals(next.threadId())
        || !stored.turnStartEntryId().equals(next.turnStartEntryId())
        || !stored.requestHeadEntryId().equals(next.requestHeadEntryId())
        || !stored.requestSpec().equals(next.requestSpec())
        || !stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException(
          "model invocation identity"
              + " (id/thread/turnStartEntry/requestHeadEntry/requestSpec/createdAt) must not change");
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
   * checkpoint 仅能在 RUNNING-&gt;RUNNING 或 RUNNING-&gt;terminal 转换时被引入或增长。进入 terminal 状态或以 READY 重试时，
   * 可以保留完全相同的 stored checkpoint 或清除它；在 terminal 内部不能再引入、增长或分叉 checkpoint。
   */
  private static void requireCheckpointTransition(ModelInvocation stored, ModelInvocation next) {
    StreamCheckpoint storedCheckpoint = stored.streamCheckpoint();
    StreamCheckpoint nextCheckpoint = next.streamCheckpoint();
    boolean allowsGrowth =
        stored.status() == ModelInvocationStatus.RUNNING
            && (next.status() == ModelInvocationStatus.RUNNING || next.status().isTerminal());
    if (storedCheckpoint == null) {
      if (nextCheckpoint != null && !allowsGrowth) {
        throw new IllegalArgumentException(
            "a checkpoint may only be introduced on a RUNNING -> RUNNING or RUNNING -> terminal"
                + " transition");
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
    if (!allowsGrowth) {
      if (!nextCheckpoint.equals(storedCheckpoint)) {
        throw new IllegalArgumentException(
            "a transition away from RUNNING or within a terminal state may only keep the exact"
                + " stored checkpoint or clear it");
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
    boolean textGrew = nextCheckpoint.text().length() > storedCheckpoint.text().length();
    boolean thinkingGrew =
        nextCheckpoint.thinking().length() > storedCheckpoint.thinking().length();
    if (!textGrew && !thinkingGrew) {
      throw new IllegalArgumentException(
          "a larger checkpoint sequence requires strict growth in text or thinking");
    }
  }

  private static void requireFailedAttemptsTransition(
      ModelInvocation stored, ModelInvocation next) {
    List<ModelAttemptFailure> storedFailures = stored.failedAttempts();
    List<ModelAttemptFailure> nextFailures = next.failedAttempts();
    boolean materializationTransition =
        stored.resultEntryId() == null && next.resultEntryId() != null;
    if (materializationTransition) {
      if (!nextFailures.isEmpty()) {
        throw new IllegalArgumentException(
            "linking a terminal result entry must clear materialized failedAttempts");
      }
      return;
    }
    boolean retryTransition =
        stored.status() == ModelInvocationStatus.RUNNING
            && next.status() == ModelInvocationStatus.READY;
    if (!retryTransition) {
      if (!storedFailures.equals(nextFailures)) {
        throw new IllegalArgumentException(
            "failedAttempts may only change on a RUNNING -> READY retry transition");
      }
      return;
    }
    if (nextFailures.size() != storedFailures.size() + 1) {
      throw new IllegalArgumentException(
          "a retry transition must append exactly one failed attempt");
    }
    for (int i = 0; i < storedFailures.size(); i++) {
      if (!storedFailures.get(i).equals(nextFailures.get(i))) {
        throw new IllegalArgumentException("failedAttempts is append-only");
      }
    }
    ModelAttemptFailure appended = nextFailures.get(nextFailures.size() - 1);
    if (appended.attempt() != stored.attempt()) {
      throw new IllegalArgumentException(
          "failed attempt must equal the current invocation attempt");
    }
    if (!isRetryable(appended.error())) {
      throw new IllegalArgumentException("failed attempt requires a retryable error");
    }
  }

  /**
   * TRANSIENT 与 INVALID_RESPONSE 复用 {@link
   * fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy}。
   */
  private static boolean isRetryable(ModelInvocationError error) {
    return error.kind() == ProviderErrorKind.TRANSIENT
        || error.kind() == ProviderErrorKind.INVALID_RESPONSE;
  }

  private static boolean isPrefix(String prefix, String value) {
    return value.startsWith(prefix);
  }

  /** READY -&gt; DISPATCHING：Work lease 已持有，Gateway admission 启动；attempt 不变。 */
  public ModelInvocation beginDispatch(Instant now) {
    return withState(
        ModelInvocationStatus.DISPATCHING, attempt, null, null, null, null, failedAttempts, now);
  }

  /**
   * DISPATCHING -&gt; FAILED：Gateway 在任何 Provider 启动之前明确拒绝了该调用；attempt 不变。 仅进行中的 dispatch 能被拒绝，且
   * {@link #fail} 拒绝 DISPATCHING，所以这是失败 dispatch 的唯一路径。
   */
  public ModelInvocation rejectDispatch(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("rejectDispatch requires DISPATCHING status");
    }
    return withState(
        ModelInvocationStatus.FAILED, attempt, null, null, error, null, failedAttempts, now);
  }

  /** DISPATCHING -&gt; READY：BUSY/OVERLOADED admission；attempt 不变。仅进行中的 dispatch 能被弹回 READY。 */
  public ModelInvocation dispatchBusy(Instant now) {
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("dispatchBusy requires DISPATCHING status");
    }
    return withState(
        ModelInvocationStatus.READY, attempt, null, null, null, null, failedAttempts, now);
  }

  /** DISPATCHING -&gt; RUNNING：Gateway 已确认启动；attempt 恰好 +1。 */
  public ModelInvocation markRunning(Instant now) {
    return withState(
        ModelInvocationStatus.RUNNING,
        Math.addExact(attempt, 1),
        null,
        null,
        null,
        null,
        failedAttempts,
        now);
  }

  /** RUNNING -&gt; READY：记录一次瞬态失败后从头重新调度；attempt 保持已确认，安全 checkpoint 被丢弃。 */
  public ModelInvocation retryReady(ModelAttemptFailure failure, Instant now) {
    Objects.requireNonNull(failure, "failure");
    if (status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    if (failure.attempt() != attempt) {
      throw new IllegalArgumentException(
          "failed attempt must equal the current invocation attempt");
    }
    if (failure.error().kind() != ProviderErrorKind.TRANSIENT
        && failure.error().kind() != ProviderErrorKind.INVALID_RESPONSE) {
      throw new IllegalArgumentException("retryReady requires a retryable error");
    }
    List<ModelAttemptFailure> nextFailures = new ArrayList<>(failedAttempts);
    nextFailures.add(failure);
    return withState(
        ModelInvocationStatus.READY, attempt, null, null, null, null, nextFailures, now);
  }

  /** RUNNING -&gt; RUNNING，附带当前 attempt 的一个单调安全 text/thinking checkpoint。 */
  public ModelInvocation checkpoint(StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    return withState(
        ModelInvocationStatus.RUNNING, attempt, checkpoint, null, null, null, failedAttempts, now);
  }

  /** RUNNING -&gt; SUCCEEDED，并附带完整的 Provider result 与最终 streamCheckpoint；attempt 必须为正数。 */
  public ModelInvocation succeed(
      ProviderResponse result, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(
        ModelInvocationStatus.SUCCEEDED,
        attempt,
        checkpoint,
        result,
        null,
        null,
        failedAttempts,
        now);
  }

  /** RUNNING -&gt; SUCCEEDED，保留当前 streamCheckpoint；attempt 必须为正数。 */
  public ModelInvocation succeed(ProviderResponse result, Instant now) {
    return succeed(result, streamCheckpoint, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED，并附带 terminal error 与可选 streamCheckpoint；attempt 不变。DISPATCHING
   * 被拒绝： 进行中的 dispatch 只能通过 {@link #rejectDispatch} 失败。
   */
  public ModelInvocation fail(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException(
          "fail requires READY or RUNNING status; a DISPATCHING invocation must use"
              + " rejectDispatch");
    }
    return withState(
        ModelInvocationStatus.FAILED,
        attempt,
        status == ModelInvocationStatus.RUNNING ? checkpoint : null,
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation fail(ModelInvocationError error, Instant now) {
    return fail(error, streamCheckpoint, now);
  }

  /**
   * READY / DISPATCHING / RUNNING -&gt; CANCELLED，并附带 terminal error 与可选 streamCheckpoint。READY 和
   * RUNNING 保持其已确认的 attempt；DISPATCHING 是 Stop 窗口，调用可能已经开始，因此 attempt 恰好 +1。
   */
  public ModelInvocation cancel(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(
        ModelInvocationStatus.CANCELLED,
        nextAttempt,
        status == ModelInvocationStatus.RUNNING ? checkpoint : null,
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation cancel(ModelInvocationError error, Instant now) {
    return cancel(error, streamCheckpoint, now);
  }

  /**
   * DISPATCHING / RUNNING -&gt; UNKNOWN，并附带 terminal error 与可选 streamCheckpoint：不确定的 admission 或恢复的
   * lease 可能已经启动了该调用，因此 DISPATCHING 时 attempt 恰好 +1，而 RUNNING 保留其已确认的 attempt。
   */
  public ModelInvocation unknown(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(
        ModelInvocationStatus.UNKNOWN,
        nextAttempt,
        status == ModelInvocationStatus.RUNNING ? checkpoint : null,
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation unknown(ModelInvocationError error, Instant now) {
    return unknown(error, streamCheckpoint, now);
  }

  /**
   * Terminal -&gt; 同一 terminal 状态，并链接执行结果 Entry；已物化的 checkpoint / failedAttempts 从 invocation 清空。
   */
  public ModelInvocation attachResultEntry(UUID resultEntryId, Instant now) {
    return withState(status, attempt, null, result, error, resultEntryId, List.of(), now);
  }

  /** 仅替换给定的当前状态字段来复制本行，并在同一处校验转换；identity、frozen request 和 createdAt 通过构造得以保留。 */
  private ModelInvocation withState(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      List<ModelAttemptFailure> failedAttempts,
      Instant now) {
    ModelInvocation next =
        new ModelInvocation(
            id,
            threadId,
            turnStartEntryId,
            requestHeadEntryId,
            requestSpec,
            status,
            attempt,
            streamCheckpoint,
            result,
            error,
            resultEntryId,
            failedAttempts,
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  private Instant effectiveMutationTime(Instant now) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(updatedAt) ? updatedAt : candidate;
  }

  private static void validateStatusFields(
      ModelInvocationStatus status,
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    int expectedRunningFailures = Math.subtractExact(attempt, 1);
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (status == ModelInvocationStatus.READY) {
      requireFailureCount(failedAttempts, attempt, "READY");
      if (streamCheckpoint != null) {
        throw new IllegalArgumentException("READY must not carry a stream checkpoint");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("READY must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.DISPATCHING) {
      requireFailureCount(failedAttempts, attempt, "DISPATCHING");
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
      requireFailureCount(failedAttempts, expectedRunningFailures, "RUNNING");
      if (result != null || error != null) {
        throw new IllegalArgumentException("RUNNING must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.SUCCEEDED) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      requireTerminalMaterialization(
          failedAttempts,
          streamCheckpoint,
          resultEntryId,
          expectedRunningFailures,
          false,
          "SUCCEEDED");
      if (result == null) {
        throw new IllegalArgumentException("SUCCEEDED requires a result");
      }
      if (error != null) {
        throw new IllegalArgumentException("SUCCEEDED must not carry an error");
      }
    } else if (status == ModelInvocationStatus.FAILED) {
      requireTerminalMaterialization(
          failedAttempts, streamCheckpoint, resultEntryId, attempt, true, "FAILED");
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
      requireTerminalMaterialization(
          failedAttempts,
          streamCheckpoint,
          resultEntryId,
          expectedRunningFailures,
          false,
          "UNKNOWN");
      if (error == null) {
        throw new IllegalArgumentException("UNKNOWN requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException("UNKNOWN must not carry a result");
      }
    } else {
      requireTerminalMaterialization(
          failedAttempts, streamCheckpoint, resultEntryId, attempt, true, "CANCELLED");
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
    }
  }

  private static void requireFailureCount(
      List<ModelAttemptFailure> failedAttempts, int expected, String status) {
    if (failedAttempts.size() != expected) {
      throw new IllegalArgumentException(status + " requires failedAttempts.size == " + expected);
    }
  }

  private static void requireTerminalMaterialization(
      List<ModelAttemptFailure> failedAttempts,
      StreamCheckpoint streamCheckpoint,
      UUID resultEntryId,
      int expectedFailures,
      boolean allowOneFewerFailure,
      String status) {
    if (resultEntryId != null) {
      if (!failedAttempts.isEmpty() || streamCheckpoint != null) {
        throw new IllegalArgumentException(
            status + " with resultEntryId must not retain materialized attempt state");
      }
      return;
    }
    boolean valid = failedAttempts.size() == expectedFailures;
    if (allowOneFewerFailure && expectedFailures > 0) {
      valid |= failedAttempts.size() == expectedFailures - 1;
    }
    if (!valid) {
      throw new IllegalArgumentException(
          status + " requires the pending failedAttempts prefix for attempt " + expectedFailures);
    }
  }
}
