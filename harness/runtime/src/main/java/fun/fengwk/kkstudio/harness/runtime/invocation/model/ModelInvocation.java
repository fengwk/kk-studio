package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
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
    Instant updatedAt,
    @JsonIgnore ProviderReplayState providerReplayState,
    @JsonIgnore ModelGroupProgress groupProgress,
    Instant requestedAt) {

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
        status,
        attempt,
        failedAttempts,
        result,
        error,
        resultEntryId,
        streamCheckpoint,
        providerReplayState);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  public ModelInvocation(
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
      Instant updatedAt,
      ProviderReplayState providerReplayState) {
    this(
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
        updatedAt,
        providerReplayState,
        null);
  }

  public ModelInvocation(
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
      Instant updatedAt,
      ProviderReplayState providerReplayState,
      ModelGroupProgress groupProgress) {
    this(
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
        updatedAt,
        providerReplayState,
        groupProgress,
        null);
  }

  public ModelInvocation(
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
    this(
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
        updatedAt,
        null,
        null);
  }

  /** 本次真实执行的逻辑选择：组取 durable 成员游标对应成员，单模型取冻结 descriptor/variant。 */
  public ModelSelection actualSelection() {
    if (requestSpec.groupExecution() == null) {
      return ModelSelection.model(requestSpec.model().modelName(), requestSpec.variant().id());
    }
    if (groupProgress == null
        || groupProgress.memberIndex() >= requestSpec.groupExecution().members().size()) {
      throw new IllegalStateException("group invocation has invalid durable progress");
    }
    ModelGroupMemberExecution member =
        requestSpec.groupExecution().members().get(groupProgress.memberIndex());
    return ModelSelection.model(member.model().modelName(), member.variant().id());
  }

  /** 在保持当前状态事实不变的前提下更新 durable 组进度；只允许与同一事务内的状态推进组合使用。 */
  public ModelInvocation withGroupProgress(ModelGroupProgress progress, Instant now) {
    if (requestSpec.groupExecution() == null) {
      throw new IllegalArgumentException("group progress requires a group request spec");
    }
    Objects.requireNonNull(progress, "progress");
    if (!progress.groupName().equals(requestSpec.groupExecution().groupName())) {
      throw new IllegalArgumentException("group progress must reference the frozen group");
    }
    if (progress.memberIndex() >= requestSpec.groupExecution().members().size()
        && status != ModelInvocationStatus.FAILED
        && status != ModelInvocationStatus.SUCCEEDED) {
      throw new IllegalArgumentException("group progress exhausted all members");
    }
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
            effectiveMutationTime(now),
            providerReplayState,
            progress,
            requestedAt);
    validateTransition(this, next);
    return next;
  }

  /**
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 request 不可变，updatedAt 不允许回退， attempt 仅在
   * DISPATCHING-&gt;RUNNING / DISPATCHING-&gt;UNKNOWN（已确认启动）以及 DISPATCHING-&gt;CANCELLED（Stop
   * 窗口内调用可能已开始）这几种情形下恰好 +1；DISPATCHING-&gt;READY 的 BUSY bounce 与 DISPATCHING-&gt;FAILED 的启动前拒绝保持
   * attempt，而执行持有者失联的恢复会把可能已发出的启动计为 attempt + 1（READY 重试或 FAILED 终止）；terminal 事实不可变 （仅 {@code
   * resultEntryId} 可从 null 附加为正数）；checkpoint 仅能在 RUNNING-&gt;RUNNING 或 RUNNING-&gt;terminal
   * 时被引入或增长；任何离开 RUNNING 或处于 terminal 内的转换，只能保留完全相同的 stored checkpoint 或清除它； terminal 结果 Entry
   * 链接时必须同时清空已经物化的 checkpoint / failedAttempts。精确 replay 始终被接受。
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
    requireGroupProgressTransition(stored, next);
    requireRequestedAtTransition(stored, next);
  }

  /** 每次确认启动都记录该次真实请求起始时间；其他转换不得改写。 */
  private static void requireRequestedAtTransition(ModelInvocation stored, ModelInvocation next) {
    if (Objects.equals(stored.requestedAt(), next.requestedAt())) {
      return;
    }
    if (next.requestedAt() == null
        || next.status() != ModelInvocationStatus.RUNNING
        || stored.status() != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException(
          "requestedAt may only change on a confirmed RUNNING start");
    }
  }

  /** 组进度只能单调推进；跨成员时成员内尝试数归零，换配置版本绝不允许。 */
  private static void requireGroupProgressTransition(ModelInvocation stored, ModelInvocation next) {
    ModelGroupProgress before = stored.groupProgress();
    ModelGroupProgress after = next.groupProgress();
    if (Objects.equals(before, after)) {
      return;
    }
    ModelGroupExecution group = stored.requestSpec().groupExecution();
    if (group == null || before == null || after == null) {
      throw new IllegalArgumentException("group progress cannot be introduced or removed");
    }
    if (next.status() != ModelInvocationStatus.READY) {
      throw new IllegalArgumentException("group progress may only change on a READY retry");
    }
    if (!before.groupName().equals(after.groupName())
        || before.groupVersion() != after.groupVersion()
        || !after.groupName().equals(group.groupName())
        || after.groupVersion() != group.groupVersion()) {
      throw new IllegalArgumentException("group progress must stay on the frozen group version");
    }
    if (after.memberIndex() < before.memberIndex()) {
      throw new IllegalArgumentException("group member index must not regress");
    }
    if (after.memberIndex() == before.memberIndex()) {
      if (after.memberAttempts() < before.memberAttempts()) {
        throw new IllegalArgumentException("member attempts must not regress");
      }
    } else if (after.memberAttempts() != 0) {
      throw new IllegalArgumentException("a new group member must start with zero member attempts");
    }
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
    if (stored.status() == ModelInvocationStatus.DISPATCHING) {
      boolean mustCharge =
          next.status() == ModelInvocationStatus.RUNNING
              || next.status() == ModelInvocationStatus.UNKNOWN
              || next.status() == ModelInvocationStatus.CANCELLED;
      if (mustCharge) {
        if (attemptDelta != 1) {
          throw new IllegalArgumentException(
              "a confirmed start or the DISPATCHING stop window must advance attempt by exactly"
                  + " one");
        }
        return;
      }
      boolean maybeCharged =
          next.status() == ModelInvocationStatus.READY
              || next.status() == ModelInvocationStatus.FAILED;
      if (maybeCharged) {
        // 未发出（BUSY bounce / 启动前拒绝）保持 attempt；失联恢复把可能已发出的启动计为 attempt + 1。
        if (attemptDelta != 0 && attemptDelta != 1) {
          throw new IllegalArgumentException(
              "a DISPATCHING -> " + next.status() + " move must keep or advance attempt by one");
        }
        return;
      }
    }
    if (attemptDelta != 0) {
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
      if (nextResultEntryId != null) {
        if (next.providerReplayState() != null) {
          throw new IllegalArgumentException(
              "linking a terminal result entry must clear providerReplayState");
        }
      } else if (!Objects.equals(stored.providerReplayState(), next.providerReplayState())) {
        throw new IllegalArgumentException("terminal providerReplayState must not change");
      }
      return;
    }
    if (!Objects.equals(storedResultEntryId, nextResultEntryId)) {
      throw new IllegalArgumentException("terminal resultEntryId must not change");
    }
    if (!Objects.equals(stored.providerReplayState(), next.providerReplayState())) {
      throw new IllegalArgumentException("terminal providerReplayState must not change");
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
    boolean confirmedRetry =
        stored.status() == ModelInvocationStatus.RUNNING
            && next.status() == ModelInvocationStatus.READY;
    boolean dispatchRetry =
        stored.status() == ModelInvocationStatus.DISPATCHING
            && next.status() == ModelInvocationStatus.READY
            && next.attempt() == Math.addExact(stored.attempt(), 1);
    boolean groupAdvance =
        confirmedRetry
            && stored.groupProgress() != null
            && next.groupProgress() != null
            && next.groupProgress().memberIndex() > stored.groupProgress().memberIndex();
    if (!confirmedRetry && !dispatchRetry) {
      if (!storedFailures.equals(nextFailures)) {
        throw new IllegalArgumentException("failedAttempts may only change on a retry transition");
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
    int consumedAttempt = dispatchRetry ? Math.addExact(stored.attempt(), 1) : stored.attempt();
    ModelAttemptFailure appended = nextFailures.get(nextFailures.size() - 1);
    if (appended.attempt() != consumedAttempt) {
      throw new IllegalArgumentException(
          "failed attempt must equal the attempt consumed by this retry");
    }
    if (!appended.error().retryCandidate() && !groupAdvance) {
      throw new IllegalArgumentException("failed attempt requires a retryable error");
    }
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
    ModelInvocation running =
        withState(
            ModelInvocationStatus.RUNNING,
            Math.addExact(attempt, 1),
            null,
            null,
            null,
            null,
            failedAttempts,
            now);
    Instant requested = now;
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        requestSpec,
        running.status(),
        running.attempt(),
        running.streamCheckpoint(),
        running.result(),
        running.error(),
        running.resultEntryId(),
        running.failedAttempts(),
        running.createdAt(),
        running.updatedAt(),
        running.providerReplayState(),
        running.groupProgress(),
        requested);
  }

  /**
   * 组内当前成员失败且不得再普通重试时，记录审计并推进到下一成员。失败可不满足普通 retry 候选（例如确定性
   * AUTHENTICATION/BILLING）；组降级是独立语义，但仍只追加一条真实 attempt 审计。
   */
  public ModelInvocation advanceGroupReady(
      ModelAttemptFailure failure, ModelGroupProgress progress, Instant now) {
    Objects.requireNonNull(failure, "failure");
    Objects.requireNonNull(progress, "progress");
    if (status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("advanceGroupReady requires RUNNING status");
    }
    if (failure.attempt() != attempt) {
      throw new IllegalArgumentException(
          "failed attempt must equal the current invocation attempt");
    }
    if (groupProgress == null || progress.memberIndex() <= groupProgress.memberIndex()) {
      throw new IllegalArgumentException("advanceGroupReady requires a later group member");
    }
    List<ModelAttemptFailure> nextFailures = new ArrayList<>(failedAttempts);
    nextFailures.add(failure);
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        requestSpec,
        ModelInvocationStatus.READY,
        attempt,
        null,
        null,
        null,
        null,
        nextFailures,
        createdAt,
        effectiveMutationTime(now),
        null,
        progress);
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
    if (!failure.error().retryCandidate()) {
      throw new IllegalArgumentException("retryReady requires a retryable error");
    }
    List<ModelAttemptFailure> nextFailures = new ArrayList<>(failedAttempts);
    nextFailures.add(failure);
    return withState(
        ModelInvocationStatus.READY, attempt, null, null, null, null, nextFailures, now);
  }

  /**
   * DISPATCHING -&gt; READY：执行持有者失联，本次启动可能已经发出但结果未被确认。把这次未确认的启动计为一个已消耗 attempt（{@code attempt +
   * 1}），记录一个 retryable failed attempt 后从头重新调度；DISPATCHING 不携带 checkpoint，旧 partial 不参与新 attempt。
   */
  public ModelInvocation retryDispatched(ModelAttemptFailure failure, Instant now) {
    Objects.requireNonNull(failure, "failure");
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("retryDispatched requires DISPATCHING status");
    }
    if (failure.attempt() != Math.addExact(attempt, 1)) {
      throw new IllegalArgumentException(
          "failed attempt must equal the unconfirmed dispatch attempt");
    }
    if (!failure.error().retryCandidate()) {
      throw new IllegalArgumentException("retryDispatched requires a retryable error");
    }
    List<ModelAttemptFailure> nextFailures = new ArrayList<>(failedAttempts);
    nextFailures.add(failure);
    return withState(
        ModelInvocationStatus.READY,
        Math.addExact(attempt, 1),
        null,
        null,
        null,
        null,
        nextFailures,
        now);
  }

  /**
   * DISPATCHING -&gt; FAILED：执行持有者失联且重试预算不允许再发起一次调用。未确认的启动仍计为一个已消耗 attempt（{@code attempt +
   * 1}）后明确终止失败；failedAttempts 保持 pending prefix 不变。
   */
  public ModelInvocation failDispatched(ModelInvocationError error, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.DISPATCHING) {
      throw new IllegalArgumentException("failDispatched requires DISPATCHING status");
    }
    return withState(
        ModelInvocationStatus.FAILED,
        Math.addExact(attempt, 1),
        null,
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  /** RUNNING -&gt; RUNNING，附带当前 attempt 的一个单调安全 text/thinking checkpoint。 */
  public ModelInvocation checkpoint(StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    return withState(
        ModelInvocationStatus.RUNNING, attempt, checkpoint, null, null, null, failedAttempts, now);
  }

  /**
   * RUNNING -&gt; SUCCEEDED，并附带完整的 Provider result、最终 streamCheckpoint 与可选的
   * providerReplayState；attempt 必须为正数。
   */
  public ModelInvocation succeed(
      ProviderResponse result,
      StreamCheckpoint checkpoint,
      ProviderReplayState providerReplayState,
      Instant now) {
    Objects.requireNonNull(result, "result");
    return withState(
        ModelInvocationStatus.SUCCEEDED,
        attempt,
        checkpoint,
        result,
        null,
        null,
        failedAttempts,
        providerReplayState,
        now);
  }

  /** RUNNING -&gt; SUCCEEDED，并附带完整的 Provider result 与最终 streamCheckpoint；attempt 必须为正数。 */
  public ModelInvocation succeed(
      ProviderResponse result, StreamCheckpoint checkpoint, Instant now) {
    return succeed(result, checkpoint, null, now);
  }

  /** RUNNING -&gt; SUCCEEDED，保留当前 streamCheckpoint；attempt 必须为正数。 */
  public ModelInvocation succeed(ProviderResponse result, Instant now) {
    return succeed(result, streamCheckpoint, null, now);
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
    return withState(
        status, attempt, streamCheckpoint, result, error, resultEntryId, failedAttempts, null, now);
  }

  private ModelInvocation withState(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      List<ModelAttemptFailure> failedAttempts,
      ProviderReplayState providerReplayState,
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
            effectiveMutationTime(now),
            providerReplayState,
            groupProgress,
            requestedAt);
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
      StreamCheckpoint streamCheckpoint,
      ProviderReplayState providerReplayState) {
    if (providerReplayState != null) {
      if (status != ModelInvocationStatus.SUCCEEDED || result == null || resultEntryId != null) {
        throw new IllegalArgumentException(
            "providerReplayState is only allowed on SUCCEEDED invocations without resultEntryId");
      }
    }
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
