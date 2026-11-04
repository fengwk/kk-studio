package fun.fengwk.kkstudio.harness.builtin.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 终态与重试：success / unknown / cancel、HTTP 状态策略、deleteWork 后的 lost ownership 与 sink 失败隔离。 */
class ModelProcessorTerminalTest extends ModelProcessorTestSupport {
  @Test
  void successTerminalWakesThreadAndPublishesTrailingGap() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("ans"));
    listener.succeed(response("answer", GenerationStopReason.COMPLETE));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals(1, model.attempt());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals(2, model.streamCheckpoint().sequence());
    assertEquals("answer", model.streamCheckpoint().text());
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("ans"), new ProviderStreamEvent.TextDelta("wer")),
        deltas(fixture.sink));
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 成功终态在 THREAD Work 已被消费后仍按升序重建 THREAD Work，不能卡在 RUNNING。 */
  @Test
  void successTerminalRecreatesConsumedThreadWork() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    fixture.store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(fixture.baseline.threadId()).orElseThrow();
          assertTrue(
              tx.work().delete(new WorkTarget(WorkTargetType.THREAD, thread.id())),
              "the thread work must be consumable before model terminal persistence");
          return null;
        });

    fixture
        .gateway
        .controlled(fixture.invocationId)
        .succeed(response("answer", GenerationStopReason.COMPLETE));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId())));
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /**
   * TRANSIENT + 策略允许：RUNNING -&gt; READY（retryReady）+ version+1 + 按策略延迟 reschedule，不请求 THREAD wake。
   */
  @Test
  void transientFailureRetriesWithPolicyDelay() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            requestSpec(),
            IMMEDIATE_STREAM);
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.event(new ProviderStreamEvent.TextDelta("partial"));
    listener.event(new ProviderStreamEvent.TextDelta("-tail"));
    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.attempt());
    assertNull(model.streamCheckpoint());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(1, model.failedAttempts().getFirst().attempt());
    assertEquals(2, model.failedAttempts().getFirst().sequence());
    assertEquals("partial-tail", model.failedAttempts().getFirst().text());
    assertEquals("", model.failedAttempts().getFirst().thinking());
    assertEquals(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "unavailable"),
        model.failedAttempts().getFirst().error());
    assertEquals(NOW, model.failedAttempts().getFirst().failedAt());
    assertEquals(NOW.plusSeconds(5), model.failedAttempts().getFirst().retryAt());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(NOW.plusSeconds(5), modelWork.availableAt());
    assertNull(modelWork.leaseToken());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(2, deltas(fixture.sink).size());
  }

  /** retry budget 耗尽：第二次 TRANSIENT 失败转为 FAILED，attempt 保持已确认值。 */
  @Test
  void retryExhaustionTerminatesFailedAfterSecondTransientFailure() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "first"));
    awaitExecutionExit(fixture);
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "second"));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(1, model.failedAttempts().size());
    assertEquals("first", model.failedAttempts().getFirst().error().message());
    assertEquals(NOW.plusSeconds(5), model.failedAttempts().getFirst().retryAt());
    assertEquals(6, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** 白名单命中的 HTTP 失败进入既有重试预算，且诊断 kind 不被改写（429 仍是 BILLING）。 */
  @Test
  void httpWhitelistedStatusRetriesKeepingDiagnosticKind() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(408, 429)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.BILLING, "quota exceeded", 429));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertEquals(1, model.failedAttempts().size());
    ModelInvocationError recorded = model.failedAttempts().getFirst().error();
    assertEquals(ProviderErrorKind.BILLING, recorded.kind());
    assertEquals(429, recorded.httpStatus());
  }

  /** 未列入白名单的 HTTP 失败立即失败，不消耗重试预算、不新增 failed attempt。 */
  @Test
  void httpUnlistedStatusFailsImmediately() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(429)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "server", 500));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(1, model.attempt());
    assertTrue(model.failedAttempts().isEmpty());
    assertEquals(ProviderErrorKind.TRANSIENT, model.error().kind());
    assertEquals(500, model.error().httpStatus());
  }

  /** 空白名单禁用 HTTP 重试：即使还有预算也直接失败。 */
  @Test
  void emptyWhitelistDisablesHttpRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of()));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "overloaded", 503));

    assertEquals(ModelInvocationStatus.FAILED, model(fixture.store, fixture.invocationId).status());
  }

  /** 非预期 3xx 保留实际状态并明确失败，不能丢掉状态后当作破损响应自动重试。 */
  @Test
  void unexpectedRedirectStatusFailsInsteadOfBrokenResponseRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                3,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(408, 429, 500, 502, 503, 504)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(
        new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, "unexpected redirect", 301));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(301, model.error().httpStatus());
    assertTrue(model.failedAttempts().isEmpty());
  }

  /** 重试判定使用当前 Invocation 冻结的 Provider 身份读取白名单，且现读最新策略快照。 */
  @Test
  void httpRetryReadsFrozenProviderIdentityAndLivePolicy() {
    AtomicReference<List<Integer>> live = new AtomicReference<>(List.of(503));
    AtomicReference<String> observedProvider = new AtomicReference<>();
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> {
              observedProvider.set(providerName);
              return new ModelHttpErrorPolicy(live.get());
            });
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "down", 503));

    assertEquals("provider", observedProvider.get());
    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    // 现读快照：把白名单改为空后，下一次失败立即终止，不重写已派发的 attempt。
    live.set(List.of());
    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "down", 503));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(1, model.failedAttempts().size());
  }

  /** 白名单命中但重试预算耗尽：记录一次 failed attempt 后明确失败，不因诊断 kind 被领域层拒绝而挂起。 */
  @Test
  void httpWhitelistedRetryExhaustionTerminatesFailed() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                1,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)),
            providerName -> new ModelHttpErrorPolicy(List.of(401, 429, 500, 502, 503, 504)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth", 401));

    assertEquals(ModelInvocationStatus.READY, model(fixture.store, fixture.invocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueControlled();
    ClaimedWork second = claim(fixture.store, fixture.invocationId);
    assertEquals(ProcessResult.STARTED, fixture.processor.process(second));
    ControlledRun secondListener = fixture.gateway.controlled(fixture.invocationId);

    secondListener.fail(new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "auth", 401));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.FAILED, model.status());
    assertEquals(2, model.attempt());
    assertEquals(1, model.failedAttempts().size());
    assertEquals(
        ProviderErrorKind.AUTHENTICATION, model.failedAttempts().getFirst().error().kind());
    assertEquals(401, model.failedAttempts().getFirst().error().httpStatus());
  }

  /** onUnknown：RUNNING 保留 attempt 转 UNKNOWN，即使错误是 TRANSIENT 也不 retry。 */
  @Test
  void unknownCallbackTerminatesWithoutRetry() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.unknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertEquals(1, model.attempt());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** Stop deleteWork 后到达的 late TRANSIENT 失败：retry 事务 ownership 校验失败，保持 RUNNING 并关闭本地执行。 */
  @Test
  void retryAfterWorkDeletionIsLost() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.fail(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertEquals(1, model.attempt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late UNKNOWN / 不可重试失败：terminal 事务 ownership 校验失败，保持 RUNNING。 */
  @Test
  void unknownAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.unknown(new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));
    awaitExecutionExit(fixture);

    assertEquals(
        ModelInvocationStatus.RUNNING, model(fixture.store, fixture.invocationId).status());
    assertEquals(2, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        1,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 事件管线失败发生在 ownership 已丢失之后：terminal 事务同样失败，仅本地关闭。 */
  @Test
  void eventFailureAfterWorkDeletionIsLost() {
    Fixture fixture = fixture(NO_RETRY, requestWithTool());
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_1", null, null));
    listener.event(new ProviderStreamEvent.ToolCallDelta(0, "call_2", null, null));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 首个事件即非法且 ownership 已丢失：terminal 事务失败，仅本地关闭，不写 durable 终态。 */
  @Test
  void nullEventAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.event(null);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** CANCELLED 失败且 ownership 已丢失：CANCELLED terminal 事务失败，仅本地关闭。 */
  @Test
  void cancelledAfterWorkDeletionIsLost() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    deleteModelWork(fixture);

    listener.fail(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.error());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** onFailed(CANCELLED)：invocation 转 CANCELLED terminal，不 retry。 */
  @Test
  void cancelledCallbackTerminatesCancelled() {
    Fixture fixture =
        fixture(
            new InvocationRetryPolicy(
                2,
                InvocationRetryBackoffStrategy.FIXED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)));
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);

    listener.fail(new ModelInvocationError(ProviderErrorKind.CANCELLED, "stopped"));
    awaitExecutionExit(fixture);

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.CANCELLED, model.status());
    assertEquals(1, model.attempt());
    assertEquals(ProviderErrorKind.CANCELLED, model.error().kind());
    assertEquals(3, thread(fixture.store, fixture.baseline.threadId()).version());
    assertEquals(
        2,
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, fixture.baseline.threadId()))
            .wakeVersion());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
  }

  /** 处理期间到达新 wake：terminal complete 只清 lease 保留行，新 wake 保持可见。 */
  @Test
  void newWakeSurvivesTerminalCompletion() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    requestModelWork(fixture);

    listener.succeed(response("answer", GenerationStopReason.COMPLETE));

    assertEquals(
        ModelInvocationStatus.SUCCEEDED, model(fixture.store, fixture.invocationId).status());
    Work modelWork =
        work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId));
    assertNotNull(modelWork);
    assertEquals(2, modelWork.wakeVersion());
    assertNull(modelWork.leaseToken());
    assertNull(modelWork.leaseUntil());
  }

  /** sink 失败只能降低实时体验：durable checkpoint / terminal 完全不受影响。 */
  @Test
  void sinkFailureDoesNotAffectDurableState() {
    Fixture fixture = fixture();
    fixture.gateway.queueControlled();
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(claim(fixture.store, fixture.invocationId)));
    ControlledRun listener = fixture.gateway.controlled(fixture.invocationId);
    fixture.sink.failure = new IllegalStateException("notification channel unavailable");

    listener.event(new ProviderStreamEvent.TextDelta("ans"));
    listener.succeed(response("answer", GenerationStopReason.COMPLETE));

    ModelInvocation model = model(fixture.store, fixture.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    assertEquals("answer", model.result().text());
    assertNotNull(model.streamCheckpoint());
    assertEquals("answer", model.streamCheckpoint().text());
    assertNull(work(fixture.store, new WorkTarget(BuiltinWorkType.MODEL, fixture.invocationId)));
    fixture.sink.failure = null;
    assertTrue(fixture.sink.events.isEmpty());
  }
}
