package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 一次 Model attempt 的终态提交组件：把 Gateway 结果收敛为成功、重试、失败、取消或 UNKNOWN，并在同一事务内写回 ModelInvocation、Thread 版本与
 * claimed Work。
 *
 * <p>事务按本 Thread 行 -&gt; ModelInvocation -&gt; claimed Work 加锁，以 Thread 行锁与 delete/Stop 串行化；唤醒
 * THREAD Work 只是 hint，不取执行树锁。所有围栏（RUNNING + attempt + claim）失败、部分写入失败或事务异常都返回 {@link
 * Applied#LOST}，绝不写任何部分状态。 成功终态在提交前冻结 Tool 历史动作，并按 {@link ModelStreamPublisher} 的快照补齐最终 checkpoint
 * 与尾部 gap，提交后才由调用方发布。
 *
 * <p>调用方必须持有 {@link ModelStreamPublisher#lock()}：终态提交与 checkpoint / 推送节奏串行，语义与拆分前一致。
 */
@Slf4j
final class ModelTerminalCommitter {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ModelGateway gateway;
  private final ModelProcessorConfig config;
  private final ClaimedWork claim;
  private final UUID threadId;
  private final UUID invocationId;
  private final int attempt;
  private final boolean ownerSettledSummary;
  private final List<ToolBinding> bindings;
  private final String providerName;
  private final ModelStreamPublisher stream;

  ModelTerminalCommitter(
      HarnessStore store,
      BuiltinStore builtinStore,
      ModelGateway gateway,
      ModelProcessorConfig config,
      ClaimedWork claim,
      UUID threadId,
      UUID invocationId,
      int attempt,
      boolean ownerSettledSummary,
      List<ToolBinding> bindings,
      String providerName,
      ModelStreamPublisher stream) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.config = Objects.requireNonNull(config, "config");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.attempt = attempt;
    this.ownerSettledSummary = ownerSettledSummary;
    this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    this.providerName = Objects.requireNonNull(providerName, "providerName");
    this.stream = Objects.requireNonNull(stream, "stream");
  }

  /** 在终态提交前冻结 Tool-owned 历史动作；Renderer 属于可插拔 Tool 代码，不在 execution 锁内调用。 */
  ProviderCompletion freezeHistoryActions(ProviderCompletion completion) {
    ProviderResponse frozen =
        ToolHistoryActions.freeze(
            completion.response(), bindings, config.toolHistoryActionResolver());
    return frozen == completion.response()
        ? completion
        : new ProviderCompletion(frozen, completion.replayState());
  }

  /** 按 Gateway 结果提交终态；{@code completion} 仅在成功且流未失败时非 null。调用方持有 stream lock。 */
  Applied commit(
      ModelGateway.Outcome outcome,
      ProviderCompletion completion,
      long successNanos,
      List<ModelStreamPublisher.Publish> publishes) {
    ModelInvocationError failure = stream.eventFailure();
    if (failure != null) {
      return finishFailure(failure, publishes);
    }
    return switch (outcome) {
      case ModelGateway.Succeeded ignored -> finishSuccess(completion, successNanos, publishes);
      case ModelGateway.Failed failed -> finishFailure(failed.error(), publishes);
      case ModelGateway.Unknown unknown -> finishUnknown(unknown.error(), publishes);
    };
  }

  private Applied finishSuccess(
      ProviderCompletion completion,
      long successNanos,
      List<ModelStreamPublisher.Publish> publishes) {
    ProviderResponse response = completion.response();
    // 仅在成功终态冻结 Harness 观测的生成计时；失败/重试/UNKNOWN/abandon 绝不虚构时长。
    Long decodeDurationMillis = stream.decodeDurationMillis(successNanos);
    if (response != null && response.stopReason() == GenerationStopReason.FILTERED) {
      ProviderResponse validatedResponse;
      try {
        validatedResponse = ModelResponseValidator.validate(response);
      } catch (RuntimeException failure) {
        log.warn(
            "invalid filtered provider response for invocation {}: {}",
            invocationId,
            ProcessorExceptions.describe(failure));
        return finishFailure(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_RESPONSE,
                ModelStreamPublisher.providerFailureMessage(failure, "invalid provider response")),
            publishes);
      }
      if (ownerSettledSummary) {
        // 过滤结果明确不可恢复：既不能当作摘要，也不无依据地重放同一请求（INVALID_REQUEST 不属于可重试 kind）。
        log.info("compaction summary was filtered by the provider for invocation {}", invocationId);
        return finishFailure(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_REQUEST,
                "compaction model response was filtered and cannot be used as a summary"),
            publishes);
      }
      ProviderResponse durableResponse =
          validatedResponse.withDecodeDurationMillis(decodeDurationMillis);
      boolean committed = safeTerminal(() -> commitSuccess(durableResponse, null, null));
      if (!committed) {
        return Applied.LOST;
      }
      stream.clearPublishes();
      return Applied.TERMINAL;
    }
    ModelStreamAccumulator.PreparedCompletion prepared;
    ProviderResponse validatedResponse;
    try {
      prepared = stream.prepareComplete(response);
      validatedResponse = ModelResponseValidator.validate(prepared.response());
    } catch (RuntimeException failure) {
      log.warn(
          "invalid provider response for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return finishFailure(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_RESPONSE,
              ModelStreamPublisher.providerFailureMessage(failure, "invalid provider response")),
          publishes);
    }
    ModelStreamPublisher.Completion accCompletion = stream.complete(prepared);
    ModelStreamPublisher.Snapshot snapshot = stream.snapshot();

    if (ownerSettledSummary && invalidCompactionFinal(validatedResponse, snapshot.text())) {
      // 摘要完整性：截断的结果（LENGTH，含截断的 tool intent）与空 final 都不能成为摘要，也绝不执行截断的 tool call。走既有调用重试
      // （同一预算、无第二套预算），上限耗尽即 FAILED；父 Join 冻结前绝不产生成功回执，也不把部分输出当摘要交给父 Agent。
      String reason =
          validatedResponse.stopReason() == GenerationStopReason.LENGTH
              ? "compaction model response was truncated before the summary completed"
              : "compaction model returned an empty summary";
      log.info(
          "invalid compaction summary for invocation {} (attempt {}): {}",
          invocationId,
          attempt,
          reason);
      return finishFailure(
          new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, reason), publishes);
    }
    StreamCheckpoint finalCheckpoint = null;
    if (!snapshot.text().isEmpty() || !snapshot.thinking().isEmpty()) {
      finalCheckpoint =
          new StreamCheckpoint(
              attempt, snapshot.lastSafeSequence(), snapshot.text(), snapshot.thinking());
    }
    final StreamCheckpoint checkpointToCommit = finalCheckpoint;

    ProviderReplayState replayState = null;
    if ((validatedResponse.stopReason() == GenerationStopReason.COMPLETE
            || validatedResponse.stopReason() == GenerationStopReason.LENGTH
            // CONTINUE 是撤下工具意图的续写终止态：下一请求必须原样回放上游 native 字段。
            || validatedResponse.stopReason() == GenerationStopReason.CONTINUE)
        && validatedResponse.toolCallDiagnostics().isEmpty()) {
      replayState = completion.replayState();
    }
    final ProviderReplayState replayStateToCommit = replayState;

    ProviderResponse durableResponse =
        validatedResponse.withDecodeDurationMillis(decodeDurationMillis);
    boolean committed =
        safeTerminal(() -> commitSuccess(durableResponse, checkpointToCommit, replayStateToCommit));
    if (!committed) {
      return Applied.LOST;
    }

    // 终态已提交：补发尚未推送的缓冲与终态补齐的 gap delta（均按 sequence 升序）。
    publishes.addAll(stream.bufferedPublishes());
    publishes.addAll(accCompletion.gaps());
    stream.clearPublishes();
    return Applied.TERMINAL;
  }

  private Applied finishFailure(
      ModelInvocationError error, List<ModelStreamPublisher.Publish> publishes) {
    Applied groupApplied = commitGroupFailure(error);
    if (groupApplied != null) {
      if (groupApplied != Applied.LOST) {
        stream.clearPublishes();
      }
      return groupApplied;
    }
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().get(), isRetryable(error), attempt);
    if (decision.retry()) {
      Duration delay = decision.delay();
      boolean committed = safeTerminal(() -> commitRetry(delay, error));
      if (committed) {
        log.info(
            "scheduled model retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
        stream.clearPublishes();
        return Applied.RETRY;
      }
      return Applied.LOST;
    }
    TerminalKind kind =
        error.kind() == ProviderErrorKind.CANCELLED ? TerminalKind.CANCELLED : TerminalKind.FAILED;
    boolean committed = safeTerminal(() -> commitTerminal(kind, error));
    if (committed) {
      stream.clearPublishes();
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  /**
   * 模型失败是否重试：先做统一候选判定（CONTEXT_LIMIT/CANCELLED 排除，非 HTTP AUTHENTICATION/BILLING 排除），带 HTTP 状态时由现读的
   * {@link fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy} 唯一决定，其余非 HTTP
   * TRANSIENT/INVALID_RESPONSE 走 共享 {@link
   * fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy} 预算。
   */
  private boolean isRetryable(ModelInvocationError error) {
    if (!error.retryCandidate()) {
      return false;
    }
    if (error.httpStatus() != null) {
      return config.httpErrorPolicyProvider().apply(providerName).allowsRetry(error.httpStatus());
    }
    return true;
  }

  /**
   * 压缩子 final 完整性：LENGTH 一律是被输出上限截断的结果（即使 provider 同时给出 tool call，截断的 tool intent 也不是可执行意图），
   * COMPLETE 只有在无 tool calls 且文本为空白时才是空摘要（纯空白与空串同样是不可用摘要，绝不能冻结成父 receipt 后被空摘要校验拒绝）。 两者都不是可用摘要。
   *
   * <p>COMPLETE / CONTINUE 的合法 tool 回合不在此列：它们按普通 Runtime 语义继续推进（模型得到真实 tool result 后再产出 final）。
   */
  private static boolean invalidCompactionFinal(ProviderResponse response, String text) {
    if (response.stopReason() == GenerationStopReason.LENGTH) {
      return true;
    }
    return response.stopReason() == GenerationStopReason.COMPLETE
        && response.toolCalls().isEmpty()
        && text.isBlank();
  }

  private Applied finishUnknown(
      ModelInvocationError error, List<ModelStreamPublisher.Publish> publishes) {
    boolean committed = safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error));
    if (committed) {
      stream.clearPublishes();
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  /** RUNNING -&gt; READY：记录瞬态失败并排程重试：{@code retryAt = failedAt + delay}，Work 按同一 delay 重排。 */
  private boolean commitRetry(Duration delay, ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return Boolean.TRUE.equals(
        inTransaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
              if (model == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                return false;
              }
              tx.updateModelInvocation(
                  model.retryReady(attemptFailure(model, error, now, now.plus(delay)), now));
              tx.threads().update(thread.touchVersion(now));
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  private boolean commitSuccess(
      ProviderResponse response,
      StreamCheckpoint finalCheckpoint,
      ProviderReplayState replayState) {
    try {
      return Boolean.TRUE.equals(
          inTransaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                if (model == null) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateModelInvocation(
                    model.succeed(response, finalCheckpoint, replayState, now));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  private boolean commitTerminal(TerminalKind kind, ModelInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          inTransaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                if (model == null) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ModelStreamPublisher.Snapshot snapshot = stream.snapshot();
                StreamCheckpoint finalCheckpoint = null;
                if (!snapshot.text().isEmpty() || !snapshot.thinking().isEmpty()) {
                  finalCheckpoint =
                      new StreamCheckpoint(
                          attempt,
                          snapshot.lastSafeSequence(),
                          snapshot.text(),
                          snapshot.thinking());
                }
                ModelInvocation next =
                    switch (kind) {
                      case FAILED -> model.fail(error, finalCheckpoint, now);
                      case CANCELLED -> model.cancel(error, finalCheckpoint, now);
                      case UNKNOWN -> model.unknown(error, finalCheckpoint, now);
                    };
                tx.updateModelInvocation(next);
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /**
   * 模型组失败的唯一推进事务：成员内普通重试、立即降级、普通重试耗尽后的冻结与成员推进、全部成员耗尽的最终失败都在同一事务中确认。
   *
   * <p>返回 {@code null} 表示当前 invocation 不是模型组，或失败属于取消、上下文溢出等专用终止，调用方继续单模型路径；返回 LOST
   * 表示所有权/状态围栏失败或事务无法提交。
   */
  private Applied commitGroupFailure(ModelInvocationError error) {
    try {
      return store.transaction(
          core -> {
            BuiltinStore.Transaction tx = builtinStore.in(core);
            Instant now = tx.now();
            ThreadState thread = tx.threads().lock(threadId).orElse(null);
            if (thread == null) {
              return Applied.LOST;
            }
            ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
            if (model == null) {
              return Applied.LOST;
            }
            if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
              return Applied.LOST;
            }
            ModelGroupExecution group = model.requestSpec().groupExecution();
            if (group == null) {
              return null;
            }
            ModelGroupProgress progress = model.groupProgress();
            if (progress == null
                || progress.memberIndex() >= group.members().size()
                || progress.groupVersion() != group.groupVersion()) {
              throw new IllegalStateException("invalid durable group progress");
            }
            boolean retryable = isRetryable(error);
            InvocationRetryDecision retryDecision =
                InvocationRetryDecision.decide(
                    config.retryPolicyProvider().get(),
                    retryable,
                    Math.addExact(progress.memberAttempts(), 1));
            ModelGroupFailureDecider.Action action =
                ModelGroupFailureDecider.decide(error, group, retryable, retryDecision.retry());
            if (action == ModelGroupFailureDecider.Action.DEDICATED_TERMINAL) {
              return null;
            }
            if (action == ModelGroupFailureDecider.Action.RETRY_CURRENT) {
              if (tx.work().lockClaimed(claim).isEmpty()) {
                return Applied.LOST;
              }
              Duration delay = retryDecision.delay();
              tx.updateModelInvocation(
                  model
                      .retryReady(attemptFailure(model, error, now, now.plus(delay)), now)
                      .withGroupProgress(progress.retryCurrent(), now));
              tx.threads().update(thread.touchVersion(now));
              tx.work().reschedule(claim, delay);
              return Applied.RETRY;
            }
            boolean exhausted = progress.memberIndex() + 1 >= group.members().size();
            if (exhausted) {
              // 终态先请求 THREAD Work，再锁 MODEL Work，维持 THREAD -> MODEL 的全局 Work 锁序。
              tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
            }
            if (tx.work().lockClaimed(claim).isEmpty()) {
              // 已写 THREAD wake 后丢失 MODEL claim：抛错回滚，避免提交无主唤醒。
              throw new ClaimLostSignal();
            }
            if (group.cooldownSeconds() > 0) {
              ModelGroupMemberExecution member = group.members().get(progress.memberIndex());
              gateway.freezeGroupMember(
                  core,
                  group.groupName(),
                  group.groupVersion(),
                  member.model().modelName(),
                  member.variant().id(),
                  now.plusSeconds(group.cooldownSeconds()));
            }
            ModelAttemptFailure failure = attemptFailure(model, error, now, now);
            if (exhausted) {
              tx.updateModelInvocation(model.failAfterAttempt(error, failure, now));
              tx.threads().update(thread.touchVersion(now));
              tx.work().complete(claim);
              return Applied.TERMINAL;
            }
            tx.updateModelInvocation(model.advanceGroupReady(failure, progress.nextMember(), now));
            tx.threads().update(thread.touchVersion(now));
            // 降级到下一成员不等待：立即重新调度同一逻辑 invocation，游标已在同事务推进。
            tx.work().reschedule(claim, Duration.ZERO);
            return Applied.RETRY;
          });
    } catch (ClaimLostSignal ignored) {
      return Applied.LOST;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model group failure for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return Applied.LOST;
    }
  }

  private <T> T inTransaction(Function<BuiltinStore.Transaction, T> work) {
    return store.transaction(tx -> work.apply(builtinStore.in(tx)));
  }

  /** 本次失败 attempt 的审计快照：当前成员身份、请求时刻与已累积的安全 partial。 */
  private ModelAttemptFailure attemptFailure(
      ModelInvocation model, ModelInvocationError error, Instant failedAt, Instant retryAt) {
    ModelStreamPublisher.Snapshot snapshot = stream.snapshot();
    return new ModelAttemptFailure(
        attempt,
        snapshot.lastSafeSequence(),
        snapshot.text(),
        snapshot.thinking(),
        model.actualSelection(),
        model.requestedAt(),
        error,
        failedAt,
        retryAt);
  }

  private boolean safeTerminal(BooleanSupplier action) {
    try {
      return action.getAsBoolean();
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return false;
    }
  }

  /** 一次终态提交的本地应用结果。 */
  enum Applied {
    /** 触发瞬态重试，对应 Work 已完成延迟重排。 */
    RETRY,

    /** 终态已持久化落盘，本地执行结束。 */
    TERMINAL,

    /** Claim 所有权已丢失或调用已被废弃，未写入任何状态。 */
    LOST
  }

  private enum TerminalKind {
    /** 收敛为明确的失败终态。 */
    FAILED,

    /** 收敛为取消终态。 */
    CANCELLED,

    /** 收敛为结果不确定的终态。 */
    UNKNOWN
  }
}
