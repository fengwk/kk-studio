package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
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
 * 与尾部 gap，提交后才由调用方发布。模型组失败的推进事务由 {@link ModelGroupFailureCommitter} 承担。
 *
 * <p>调用方必须持有 {@link ModelStreamPublisher#lock()}：终态提交与 checkpoint / 推送节奏串行。
 */
@Slf4j
final class ModelTerminalCommitter {

  private static final String INVALID_RESPONSE_FALLBACK = "invalid provider response";

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ModelProcessorConfig config;
  private final ClaimedWork claim;
  private final UUID threadId;
  private final UUID invocationId;
  private final int attempt;
  private final boolean ownerSettledSummary;
  private final List<ToolBinding> bindings;
  private final String providerName;
  private final ModelStreamPublisher stream;
  private final ModelGroupFailureCommitter groupFailures;

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
      boolean groupInvocation,
      ModelStreamPublisher stream) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.config = Objects.requireNonNull(config, "config");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.attempt = attempt;
    this.ownerSettledSummary = ownerSettledSummary;
    this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    this.providerName = Objects.requireNonNull(providerName, "providerName");
    this.stream = Objects.requireNonNull(stream, "stream");
    this.groupFailures =
        new ModelGroupFailureCommitter(
            this.store,
            builtinStore,
            Objects.requireNonNull(gateway, "gateway"),
            config,
            this.claim,
            threadId,
            invocationId,
            attempt,
            providerName,
            groupInvocation,
            stream);
  }

  /** 在终态提交前冻结 Tool-owned 历史动作；Renderer 属于可插拔 Tool 代码，不在 execution 锁内调用。 */
  ProviderCompletion freezeHistoryActions(ProviderCompletion completion) {
    ProviderResponse frozen =
        ToolHistoryActions.freeze(
            completion.response(), bindings, config.toolHistoryActionResolver());
    return frozen == completion.response() ? completion : new ProviderCompletion(frozen);
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
      return finishFiltered(response, decodeDurationMillis, publishes);
    }
    return finishCompleted(response, decodeDurationMillis, publishes);
  }

  /** FILTERED 是 provider 的明确撤回：只校验形状，不补齐流式 gap，也不当作摘要使用。 */
  private Applied finishFiltered(
      ProviderResponse response,
      Long decodeDurationMillis,
      List<ModelStreamPublisher.Publish> publishes) {
    ProviderResponse validatedResponse;
    try {
      validatedResponse = ModelResponseValidator.validate(response);
    } catch (RuntimeException failure) {
      log.warn(
          "invalid filtered provider response for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return finishFailure(invalidResponse(failure), publishes);
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
    boolean committed = safeTerminal(() -> commitSuccess(durableResponse, null));
    if (!committed) {
      return Applied.LOST;
    }
    stream.clearPublishes();
    return Applied.TERMINAL;
  }

  /** 正常成功终态：校验 final response，补齐尾部 gap 与最终 checkpoint，提交后再发布。 */
  private Applied finishCompleted(
      ProviderResponse response,
      Long decodeDurationMillis,
      List<ModelStreamPublisher.Publish> publishes) {
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
      return finishFailure(invalidResponse(failure), publishes);
    }
    ModelStreamPublisher.Completion accCompletion = stream.complete(prepared);
    ModelStreamPublisher.Snapshot snapshot = stream.snapshot();
    if (ownerSettledSummary && invalidCompactionFinal(validatedResponse, snapshot.text())) {
      // 摘要完整性：截断的结果（LENGTH，含截断的 tool intent）与空 final 都不能成为摘要，也绝不执行截断的 tool call。走既有调用重试
      // （同一预算、无第二套预算），上限耗尽即 FAILED；父 Join 冻结前绝不产生成功回执，也不把部分输出当摘要交给父 Agent。
      String reason = invalidCompactionReason(validatedResponse);
      log.info(
          "invalid compaction summary for invocation {} (attempt {}): {}",
          invocationId,
          attempt,
          reason);
      return finishFailure(
          new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, reason), publishes);
    }
    ProviderResponse durableResponse =
        validatedResponse.withDecodeDurationMillis(decodeDurationMillis);
    StreamCheckpoint finalCheckpoint = finalCheckpoint(snapshot);
    boolean committed = safeTerminal(() -> commitSuccess(durableResponse, finalCheckpoint));
    if (!committed) {
      return Applied.LOST;
    }
    // 终态已提交：补发尚未推送的缓冲与终态补齐的 gap delta（均按 sequence 升序）。
    publishes.addAll(stream.bufferedPublishes());
    publishes.addAll(accCompletion.gaps());
    stream.clearPublishes();
    return Applied.TERMINAL;
  }

  private static ModelInvocationError invalidResponse(RuntimeException failure) {
    return new ModelInvocationError(
        ProviderErrorKind.INVALID_RESPONSE,
        ModelStreamPublisher.providerFailureMessage(failure, INVALID_RESPONSE_FALLBACK));
  }

  private Applied finishFailure(
      ModelInvocationError error, List<ModelStreamPublisher.Publish> publishes) {
    Applied groupApplied = groupFailures.commit(error);
    if (groupApplied != null) {
      if (groupApplied != Applied.LOST) {
        stream.clearPublishes();
      }
      return groupApplied;
    }
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().get(),
            ModelFailureRetry.isRetryable(error, config, providerName),
            attempt);
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

  private static String invalidCompactionReason(ProviderResponse response) {
    return response.stopReason() == GenerationStopReason.LENGTH
        ? "compaction model response was truncated before the summary completed"
        : "compaction model returned an empty summary";
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
                  model.retryReady(
                      ModelAttemptFailures.fromStream(
                          attempt, stream.snapshot(), model, error, now, now.plus(delay)),
                      now));
              tx.threads().update(thread.touchVersion(now));
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  private boolean commitSuccess(ProviderResponse response, StreamCheckpoint finalCheckpoint) {
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
                tx.updateModelInvocation(model.succeed(response, finalCheckpoint, now));
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
                StreamCheckpoint finalCheckpoint = finalCheckpoint(stream.snapshot());
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

  /** 有内容时才把流式快照冻结为 terminal checkpoint；空内容不产生空 checkpoint。 */
  private StreamCheckpoint finalCheckpoint(ModelStreamPublisher.Snapshot snapshot) {
    if (snapshot.text().isEmpty() && snapshot.thinking().isEmpty()) {
      return null;
    }
    return new StreamCheckpoint(
        attempt, snapshot.lastSafeSequence(), snapshot.text(), snapshot.thinking());
  }

  private <T> T inTransaction(Function<BuiltinStore.Transaction, T> work) {
    return store.transaction(tx -> work.apply(builtinStore.in(tx)));
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
