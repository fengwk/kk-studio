package fun.fengwk.kkstudio.harness.builtin.port.store;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Model Invocation 的持久化当前状态。
 *
 * <p>{@code attempt} 统计已持久化 RUNNING 的执行次数（RUNNING 先于任何请求发出）；BUSY 或启动前的明确拒绝不会增加它。 {@code
 * resultEntryId} 将执行结果链接到 Session 历史，且仅（在某些情况下）出现于 terminal 状态。 {@code streamCheckpoint} 是当前
 * attempt 的安全 partial，永远不是第二个 result。{@code failedAttempts} 是 append-only 的瞬态失败 attempt 审计，不属于对话语义。
 *
 * <p>Store 必须在每次 {@code update*} 写入前调用 {@link #validateTransition}。
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
    requireConsecutiveFailures(failedAttempts, attempt);
    if (streamCheckpoint != null && streamCheckpoint.attempt() != attempt) {
      throw new IllegalArgumentException("streamCheckpoint attempt must match invocation attempt");
    }
    ModelInvocationStatusFields.validate(
        status, attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
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

  private static void requireConsecutiveFailures(
      List<ModelAttemptFailure> failedAttempts, int attempt) {
    for (int index = 0; index < failedAttempts.size(); index++) {
      ModelAttemptFailure failure = failedAttempts.get(index);
      if (failure.attempt() != index + 1) {
        throw new IllegalArgumentException("failedAttempts must contain consecutive attempts 1..N");
      }
      if (failure.attempt() > attempt) {
        throw new IllegalArgumentException("failed attempt must not exceed invocation attempt");
      }
    }
  }

  /** 当前成功回复实际声明的 reasoning effort；未声明或关闭时为 null。 */
  public String actualEffort() {
    ModelVariant variant =
        requestSpec.groupExecution() == null
            ? requestSpec.variant()
            : currentGroupMember().variant();
    return variant.reasoningOff() ? null : variant.reasoningEffort();
  }

  /** 本次真实执行的逻辑选择：组取 durable 成员游标对应成员，单模型取冻结 descriptor/variant。 */
  public ModelSelection actualSelection() {
    if (requestSpec.groupExecution() == null) {
      return ModelSelection.model(requestSpec.model().modelName(), requestSpec.variant().id());
    }
    ModelGroupMemberExecution member = currentGroupMember();
    return ModelSelection.model(member.model().modelName(), member.variant().id());
  }

  private ModelGroupMemberExecution currentGroupMember() {
    ModelGroupExecution group = requestSpec.groupExecution();
    if (groupProgress == null || groupProgress.memberIndex() >= group.members().size()) {
      throw new IllegalStateException("group invocation has invalid durable progress");
    }
    return group.members().get(groupProgress.memberIndex());
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
        copy(
            status,
            attempt,
            streamCheckpoint,
            result,
            error,
            resultEntryId,
            failedAttempts,
            now,
            progress,
            requestedAt);
    validateTransition(this, next);
    return next;
  }

  /**
   * 校验 {@code next} 是已存储行 {@code stored} 的合法转换：identity 与 request 不可变，updatedAt 不允许回退， attempt 仅在
   * READY-&gt;RUNNING 时恰好 +1（RUNNING 持久化先于请求发出），其余转换保持不变；terminal 事实不可变 （仅 {@code resultEntryId} 可从
   * null 附加为正数）；checkpoint 仅能在 RUNNING-&gt;RUNNING 或 RUNNING-&gt;terminal 时被引入或增长；任何离开 RUNNING 或处于
   * terminal 内的转换，只能保留完全相同的 stored checkpoint 或清除它； terminal 结果 Entry 链接时必须同时清空已经物化的 checkpoint /
   * failedAttempts。精确 replay 始终被接受。
   */
  public static void validateTransition(ModelInvocation stored, ModelInvocation next) {
    ModelInvocationTransitions.validate(stored, next);
  }

  /** READY -&gt; RUNNING：先于任何请求发出持久化；attempt 恰好 +1，并记录本次真实请求起始时间。 */
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
    return copy(
        running.status(),
        running.attempt(),
        running.streamCheckpoint(),
        running.result(),
        running.error(),
        running.resultEntryId(),
        running.failedAttempts(),
        running.updatedAt(),
        running.groupProgress(),
        now);
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
    return copy(
        ModelInvocationStatus.READY,
        attempt,
        null,
        null,
        null,
        null,
        appendFailure(failure),
        now,
        progress,
        requestedAt);
  }

  /** RUNNING -&gt; FAILED：保留最后一次真实失败审计，供组全部成员耗尽后的终止事实使用。 */
  public ModelInvocation failAfterAttempt(
      ModelInvocationError error, ModelAttemptFailure failure, Instant now) {
    Objects.requireNonNull(error, "error");
    Objects.requireNonNull(failure, "failure");
    if (status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("failAfterAttempt requires RUNNING status");
    }
    if (failure.attempt() != attempt) {
      throw new IllegalArgumentException(
          "failed attempt must equal the current invocation attempt");
    }
    return withState(
        ModelInvocationStatus.FAILED,
        attempt,
        null,
        null,
        error,
        null,
        appendFailure(failure),
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
    if (!failure.error().retryCandidate()) {
      throw new IllegalArgumentException("retryReady requires a retryable error");
    }
    return withState(
        ModelInvocationStatus.READY, attempt, null, null, null, null, appendFailure(failure), now);
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
   * READY / RUNNING -&gt; FAILED，并附带 terminal error 与可选 streamCheckpoint；attempt 不变。READY
   * 用于启动前的明确拒绝。
   */
  public ModelInvocation fail(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.RUNNING) {
      throw new IllegalArgumentException("fail requires READY or RUNNING status");
    }
    return withState(
        ModelInvocationStatus.FAILED,
        attempt,
        runningCheckpoint(checkpoint),
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation fail(ModelInvocationError error, Instant now) {
    return fail(error, streamCheckpoint, now);
  }

  /** READY / RUNNING -&gt; CANCELLED，并附带 terminal error 与可选 streamCheckpoint；attempt 不变。 */
  public ModelInvocation cancel(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    return withState(
        ModelInvocationStatus.CANCELLED,
        attempt,
        runningCheckpoint(checkpoint),
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation cancel(ModelInvocationError error, Instant now) {
    return cancel(error, streamCheckpoint, now);
  }

  /** RUNNING -&gt; UNKNOWN，并附带 terminal error 与可选 streamCheckpoint：请求可能已发出但结果无法确认；attempt 不变。 */
  public ModelInvocation unknown(
      ModelInvocationError error, StreamCheckpoint checkpoint, Instant now) {
    Objects.requireNonNull(error, "error");
    return withState(
        ModelInvocationStatus.UNKNOWN,
        attempt,
        runningCheckpoint(checkpoint),
        null,
        error,
        null,
        failedAttempts,
        now);
  }

  public ModelInvocation unknown(ModelInvocationError error, Instant now) {
    return unknown(error, streamCheckpoint, now);
  }

  /** 只有 RUNNING 会把 partial 带入 terminal；READY 从未执行，不能携带 checkpoint。 */
  private StreamCheckpoint runningCheckpoint(StreamCheckpoint checkpoint) {
    return status == ModelInvocationStatus.RUNNING ? checkpoint : null;
  }

  /**
   * Terminal -&gt; 同一 terminal 状态，并链接执行结果 Entry；已物化的 checkpoint / failedAttempts 从 invocation 清空。
   */
  public ModelInvocation attachResultEntry(UUID resultEntryId, Instant now) {
    return withState(status, attempt, null, result, error, resultEntryId, List.of(), now);
  }

  private List<ModelAttemptFailure> appendFailure(ModelAttemptFailure failure) {
    List<ModelAttemptFailure> nextFailures = new ArrayList<>(failedAttempts);
    nextFailures.add(failure);
    return nextFailures;
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
        copy(
            status,
            attempt,
            streamCheckpoint,
            result,
            error,
            resultEntryId,
            failedAttempts,
            now,
            groupProgress,
            requestedAt);
    validateTransition(this, next);
    return next;
  }

  /** 以本行为模板复制出候选下一状态；不校验转换，调用方决定是否及何时校验。 */
  private ModelInvocation copy(
      ModelInvocationStatus status,
      int attempt,
      StreamCheckpoint streamCheckpoint,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      List<ModelAttemptFailure> failedAttempts,
      Instant updatedAt,
      ModelGroupProgress groupProgress,
      Instant requestedAt) {
    return new ModelInvocation(
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
        groupProgress,
        requestedAt);
  }
}
