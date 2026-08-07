package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Model invocation 的 durable 当前状态。
 *
 * <p>{@code attempt} 统计已确认的 Provider 调用启动次数；BUSY/OVERLOADED 或启动前的明确拒绝不会增加它。 {@code resultEntryId}
 * 将执行结果链接到 Session 历史，且仅（在某些情况下）出现于 terminal 状态。 {@code streamCheckpoint} 是当前 attempt 的安全
 * partial，永远不是第二个 result。
 *
 * <p>{@code DISPATCHING} 表示 Work lease 已持有，Gateway admission 进行中：外部服务是否接受调用 尚未被 durable
 * 确认。所有状态变更都通过下面的纯转换方法进行；Store 必须在每次 {@code update*} 写入 之前调用 {@link #validateTransition}，从而把直接构造
 * record 的场景限制在持久化解码。
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
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 request 不可变，updatedAt 不允许回退， attempt 仅在
   * DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN（已确认启动）以及 DISPATCHING-&gt;CANCELLED（Stop
   * 窗口内调用可能已开始）这几种情形下恰好 +1；terminal 事实不可变 （仅 {@code resultEntryId} 可从 null 附加为正数）；checkpoint 仅能在
   * RUNNING-&gt;RUNNING 时被引入 或增长；任何离开 RUNNING 或处于 terminal 内的转换，只能保留完全相同的 stored checkpoint 或清除它。
   * 精确 replay 始终被接受。
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
   * checkpoint 仅能在 RUNNING-&gt;RUNNING 转换时被引入或增长。进入 terminal 状态或以 READY 重试时， 可以保留完全相同的 stored
   * checkpoint 或清除它；在 terminal 内部不能再引入、增长或分叉 checkpoint。
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

  /** READY -&gt; DISPATCHING：Work lease 已持有，Gateway admission 启动；attempt 不变。 */
  public ModelInvocation beginDispatch(Instant now) {
    return withState(ModelInvocationStatus.DISPATCHING, attempt, null, null, null, null, now);
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
    return withState(ModelInvocationStatus.FAILED, attempt, null, null, error, null, now);
  }

  /** DISPATCHING -&gt; READY：BUSY/OVERLOADED admission；attempt 不变。仅进行中的 dispatch 能被弹回 READY。 */
  public ModelInvocation dispatchBusy(Instant now) {
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("dispatchBusy requires DISPATCHING status");
    }
    return withState(ModelInvocationStatus.READY, attempt, null, null, null, null, now);
  }

  /** DISPATCHING -&gt; RUNNING：Gateway 已确认启动；attempt 恰好 +1。 */
  public ModelInvocation markRunning(Instant now) {
    return withState(
        ModelInvocationStatus.RUNNING, Math.addExact(attempt, 1), null, null, null, null, now);
  }

  /** RUNNING -&gt; READY：执行端报告了可重试的失败，因此同一个 attempt 被从头重新调度；attempt 保持已确认， 安全 checkpoint 被丢弃。 */
  public ModelInvocation retryReady(Instant now) {
    if (status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("retryReady requires RUNNING status");
    }
    return withState(ModelInvocationStatus.READY, attempt, null, null, null, null, now);
  }

  /** RUNNING -&gt; RUNNING，附带当前 attempt 的一个单调安全 text/thinking checkpoint。 */
  public ModelInvocation checkpoint(StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    return withState(ModelInvocationStatus.RUNNING, attempt, checkpoint, null, null, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED，并附带完整的 Provider result；attempt 必须为正数。 */
  public ModelInvocation succeed(ProviderResponse result, Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(
        ModelInvocationStatus.SUCCEEDED, attempt, streamCheckpoint, result, null, null, now);
  }

  /**
   * READY / RUNNING -&gt; FAILED，并附带一个 terminal error；attempt 不变。DISPATCHING 被拒绝： 进行中的 dispatch
   * 只能通过 {@link #rejectDispatch} 失败。
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
   * READY / DISPATCHING / RUNNING -&gt; CANCELLED，并附带一个 terminal error。READY 和 RUNNING 保持其已确认的
   * attempt；DISPATCHING 是 Stop 窗口，调用可能已经开始，因此 attempt 恰好 +1。
   */
  public ModelInvocation cancel(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(ModelInvocationStatus.CANCELLED, nextAttempt, null, null, error, null, now);
  }

  /**
   * DISPATCHING / RUNNING -&gt; UNKNOWN，并附带一个 terminal error：不确定的 admission 或恢复的 lease
   * 可能已经启动了该调用，因此 DISPATCHING 时 attempt 恰好 +1，而 RUNNING 保留其已确认的 attempt。
   */
  public ModelInvocation unknown(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    int nextAttempt =
        status == ModelInvocationStatus.DISPATCHING ? Math.addExact(attempt, 1) : attempt;
    return withState(ModelInvocationStatus.UNKNOWN, nextAttempt, null, null, error, null, now);
  }

  /** Terminal -&gt; 同一 terminal 状态，并链接执行结果 Entry；可以清除安全 checkpoint，其他 terminal 事实保持不变。 */
  public ModelInvocation attachResultEntry(long resultEntryId, Instant now) {
    return withState(status, attempt, null, result, error, resultEntryId, now);
  }

  /** 仅替换给定的当前状态字段来复制本行，并在同一处校验转换；identity、frozen request 和 createdAt 通过构造得以保留。 */
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
