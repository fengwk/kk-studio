package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 模型组失败的唯一推进事务：成员内普通重试、立即降级、普通重试耗尽后的冻结与成员推进、全部成员耗尽的最终失败都在同一事务中确认。
 *
 * <p>事务与单模型终态提交使用相同的锁序（本 Thread 行 -&gt; ModelInvocation -&gt; claimed Work）；仅在终态分支先 request THREAD
 * Work 再锁定 MODEL Work，维持全局 THREAD -&gt; MODEL 锁序。任何围栏失败都不写任何部分状态。
 */
@Slf4j
final class ModelGroupFailureCommitter {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ModelGateway gateway;
  private final ModelProcessorConfig config;
  private final ClaimedWork claim;
  private final UUID threadId;
  private final UUID invocationId;
  private final int attempt;
  private final String providerName;
  private final boolean groupInvocation;
  private final ModelStreamPublisher stream;

  ModelGroupFailureCommitter(
      HarnessStore store,
      BuiltinStore builtinStore,
      ModelGateway gateway,
      ModelProcessorConfig config,
      ClaimedWork claim,
      UUID threadId,
      UUID invocationId,
      int attempt,
      String providerName,
      boolean groupInvocation,
      ModelStreamPublisher stream) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.config = Objects.requireNonNull(config, "config");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.attempt = attempt;
    this.providerName = Objects.requireNonNull(providerName, "providerName");
    this.groupInvocation = groupInvocation;
    this.stream = Objects.requireNonNull(stream, "stream");
  }

  /**
   * 提交一次模型组失败推进。
   *
   * <p>返回 {@code null} 表示当前 invocation 不是模型组（冻结请求没有 groupExecution），或失败属于取消、上下文溢出等专用终止，
   * 调用方继续单模型路径；返回 LOST 表示所有权/状态围栏失败或事务无法提交。非组调用不开启事务、不加锁。
   */
  ModelTerminalCommitter.Applied commit(ModelInvocationError error) {
    if (!groupInvocation) {
      return null;
    }
    try {
      return store.transaction(core -> commitLocked(core, builtinStore.in(core), error));
    } catch (ClaimLostSignal ignored) {
      return ModelTerminalCommitter.Applied.LOST;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model group failure for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return ModelTerminalCommitter.Applied.LOST;
    }
  }

  private ModelTerminalCommitter.Applied commitLocked(
      HarnessStore.Transaction core, BuiltinStore.Transaction tx, ModelInvocationError error) {
    Instant now = tx.now();
    ThreadState thread = tx.threads().lock(threadId).orElse(null);
    if (thread == null) {
      return ModelTerminalCommitter.Applied.LOST;
    }
    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
    if (model == null || !isCurrentRunningAttempt(model)) {
      return ModelTerminalCommitter.Applied.LOST;
    }
    ModelGroupExecution group = model.requestSpec().groupExecution();
    if (group == null) {
      return null;
    }
    ModelGroupProgress progress = requireValidProgress(model, group);
    boolean retryable = ModelFailureRetry.isRetryable(error, config, providerName);
    InvocationRetryDecision retryDecision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().get(),
            retryable,
            Math.addExact(progress.memberAttempts(), 1));
    return switch (ModelGroupFailureDecider.decide(
        error, group, retryable, retryDecision.retry())) {
      case DEDICATED_TERMINAL -> null;
      case RETRY_CURRENT -> retryCurrent(
          tx, thread, model, progress, retryDecision.delay(), error, now);
      case ADVANCE -> advance(core, tx, thread, model, group, progress, error, now);
    };
  }

  private boolean isCurrentRunningAttempt(ModelInvocation model) {
    return model.status() == ModelInvocationStatus.RUNNING && model.attempt() == attempt;
  }

  private ModelGroupProgress requireValidProgress(
      ModelInvocation model, ModelGroupExecution group) {
    ModelGroupProgress progress = model.groupProgress();
    if (progress == null
        || progress.memberIndex() >= group.members().size()
        || progress.groupVersion() != group.groupVersion()) {
      throw new IllegalStateException("invalid durable group progress");
    }
    return progress;
  }

  /** 成员内普通重试：记录审计并推进成员尝试预算，Work 按同一 delay 重排。 */
  private ModelTerminalCommitter.Applied retryCurrent(
      BuiltinStore.Transaction tx,
      ThreadState thread,
      ModelInvocation model,
      ModelGroupProgress progress,
      Duration delay,
      ModelInvocationError error,
      Instant now) {
    if (tx.work().lockClaimed(claim).isEmpty()) {
      return ModelTerminalCommitter.Applied.LOST;
    }
    ModelAttemptFailure failure =
        ModelAttemptFailures.fromStream(
            attempt, stream.snapshot(), model, error, now, now.plus(delay));
    tx.updateModelInvocation(
        model.retryReady(failure, now).withGroupProgress(progress.retryCurrent(), now));
    tx.threads().update(thread.touchVersion(now));
    tx.work().reschedule(claim, delay);
    return ModelTerminalCommitter.Applied.RETRY;
  }

  /**
   * 冻结当前成员并降级到下一成员；全部成员耗尽时以失败终态收敛。
   *
   * <p>终态先请求 THREAD Work 再锁 MODEL Work；降级立即重新调度同一逻辑 invocation。
   */
  private ModelTerminalCommitter.Applied advance(
      HarnessStore.Transaction core,
      BuiltinStore.Transaction tx,
      ThreadState thread,
      ModelInvocation model,
      ModelGroupExecution group,
      ModelGroupProgress progress,
      ModelInvocationError error,
      Instant now) {
    boolean exhausted = progress.memberIndex() + 1 >= group.members().size();
    if (exhausted) {
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
    }
    if (tx.work().lockClaimed(claim).isEmpty()) {
      // 已写 THREAD wake 后丢失 MODEL claim：抛错回滚，避免提交无主唤醒。
      throw new ClaimLostSignal();
    }
    freezeCurrentMember(core, group, progress, now);
    ModelAttemptFailure failure =
        ModelAttemptFailures.fromStream(attempt, stream.snapshot(), model, error, now, now);
    if (exhausted) {
      tx.updateModelInvocation(model.failAfterAttempt(error, failure, now));
      tx.threads().update(thread.touchVersion(now));
      tx.work().complete(claim);
      return ModelTerminalCommitter.Applied.TERMINAL;
    }
    tx.updateModelInvocation(model.advanceGroupReady(failure, progress.nextMember(), now));
    tx.threads().update(thread.touchVersion(now));
    tx.work().reschedule(claim, Duration.ZERO);
    return ModelTerminalCommitter.Applied.RETRY;
  }

  private void freezeCurrentMember(
      HarnessStore.Transaction core,
      ModelGroupExecution group,
      ModelGroupProgress progress,
      Instant now) {
    if (group.cooldownSeconds() <= 0) {
      return;
    }
    ModelGroupMemberExecution member = group.members().get(progress.memberIndex());
    gateway.freezeGroupMember(
        core,
        group.groupName(),
        group.groupVersion(),
        member.model().modelName(),
        member.variant().id(),
        now.plusSeconds(group.cooldownSeconds()));
  }
}
