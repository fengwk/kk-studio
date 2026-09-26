package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * ModelExecution 流式生成计时（{@code decodeDurationMillis}）的精确采集边界。
 *
 * <p>覆盖：首个非空 text / thinking / tool-call name-or-arguments delta 的判定（空 delta 与 id-only fragment
 * 不计）、 无事件时缺失、重复成功回调、abandon / retry / UNKNOWN 绝不虚构时长，以及成功计时经 durable {@code
 * ModelInvocation.result} 由 ThreadProcessor 物化到 ASSISTANT metadata 的完整链路。计时源是脚本化单调纳秒，测试不依赖真实时间、不
 * sleep。
 */
class ModelExecutionDecodeDurationTest {

  private static final Instant NOW = ThreadProcessorTestSupport.NOW;
  private static final Duration FALLBACK_DELAY = Duration.ofSeconds(1);
  private static final StreamFlushConfig FLUSH_CONFIG =
      new StreamFlushConfig(Duration.ofMinutes(1), 1024, 1024 * 1024);
  private static final InvocationRetryPolicy NO_RETRY =
      new InvocationRetryPolicy(
          0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final InvocationRetryPolicy ONE_RETRY =
      new InvocationRetryPolicy(
          1, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0, 0, 0, 2);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  private final List<ExecutorService> executorsToClose = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (ExecutorService executor : executorsToClose) {
      executor.shutdownNow();
    }
  }

  /** 空 delta 与 id-only tool fragment 不启动计时，只有首非空 text delta 到成功回调的窗口被记录。 */
  @Test
  void firstNonEmptyDeltaToSuccessCallbackDefinesDuration() {
    Harness harness = new Harness(NO_RETRY, 1_000_000_000L, 1_250_500_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta(""));
    harness.execution.onEvent(new ProviderStreamEvent.ThinkingDelta(""));
    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call-1", null, null));
    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("hi"));
    harness.execution.onSucceeded(new ProviderCompletion(toolResponse("hi")));

    assertEquals(250L, persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** 纯 id-only tool fragment 与空 delta 永远不构成可信计时：成功回调后仍为缺失。 */
  @Test
  void emptyAndIdOnlyDeltasLeaveDurationAbsent() {
    Harness harness = new Harness(NO_RETRY, 7_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta(""));
    harness.execution.onEvent(new ProviderStreamEvent.ThinkingDelta(""));
    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call-1", null, null));
    // argumentsJson 存在但为空同样不构成模型输出。
    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call-1", null, ""));
    harness.execution.onSucceeded(new ProviderCompletion(toolResponse()));

    assertNull(persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** thinking 也能启动计时：首个非空 thinking delta 到成功回调（后续 text delta 不重置起点）。 */
  @Test
  void firstThinkingDeltaStartsTiming() {
    Harness harness = new Harness(NO_RETRY, 2_000_000_000L, 2_500_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.ThinkingDelta("think"));
    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("answer"));
    harness.execution.onSucceeded(new ProviderCompletion(response("answer")));

    assertEquals(500L, persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** tool call 的 name / arguments fragment 属于模型输出：id-only fragment 之后的首个 arguments delta 启动计时。 */
  @Test
  void firstToolCallArgumentsDeltaStartsTiming() {
    Harness harness = new Harness(NO_RETRY, 3_000_000_000L, 3_100_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, "call-1", null, null));
    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"q\":1}"));
    harness.execution.onSucceeded(new ProviderCompletion(toolResponse()));

    assertEquals(100L, persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** tool call 的 name fragment 同样属于模型输出，可以直接启动计时。 */
  @Test
  void firstToolCallNameDeltaStartsTiming() {
    Harness harness = new Harness(NO_RETRY, 4_000_000_000L, 4_400_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.ToolCallDelta(0, null, "bash", null));
    harness.execution.onSucceeded(new ProviderCompletion(toolResponse()));

    assertEquals(400L, persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** 成功回调前完全没有 delta（例如预填响应）时不得虚构计时。 */
  @Test
  void noEventsLeaveDurationAbsent() {
    Harness harness = new Harness(NO_RETRY, 9_000_000L);

    harness.execution.onSucceeded(new ProviderCompletion(response("hello")));

    assertNull(persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** 重复成功回调只保留首个观察时刻，且不产生第二次终态落地。 */
  @Test
  void duplicateSuccessCallbackKeepsFirstObservation() {
    Harness harness = new Harness(NO_RETRY, 1_000_000L, 3_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("x"));
    harness.execution.onSucceeded(new ProviderCompletion(response("x")));
    harness.execution.onSucceeded(new ProviderCompletion(response("x")));

    assertEquals(2L, persistedDuration(harness));
    assertEquals(
        "x", ThreadProcessorTestSupport.model(harness.store, harness.invocationId).result().text());
    harness.nano.assertExhausted();
  }

  /** abandon 后的成功回调被丢弃：durable RUNNING 不产生任何计时或结果。 */
  @Test
  void abandonedExecutionDoesNotFabricateDuration() {
    Harness harness = new Harness(NO_RETRY, 1_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("x"));
    harness.execution.abandon();
    harness.execution.onSucceeded(new ProviderCompletion(response("x")));

    ModelInvocation model = ThreadProcessorTestSupport.model(harness.store, harness.invocationId);
    assertEquals(ModelInvocationStatus.RUNNING, model.status());
    assertNull(model.result());
    harness.nano.assertExhausted();
  }

  /** 瞬态失败转 retry 是失败 attempt：绝不把已观察 delta 的时长写进 result。 */
  @Test
  void transientRetryNeverPersistsDuration() {
    Harness harness = new Harness(ONE_RETRY, 1_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("x"));
    harness.execution.onFailed(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider down"));

    ModelInvocation model = ThreadProcessorTestSupport.model(harness.store, harness.invocationId);
    assertEquals(ModelInvocationStatus.READY, model.status());
    assertNull(model.result());
    assertEquals(1, model.failedAttempts().size());
    harness.nano.assertExhausted();
  }

  /** UNKNOWN 终态无法确认执行结果：不得携带任何计时。 */
  @Test
  void unknownTerminalNeverPersistsDuration() {
    Harness harness = new Harness(NO_RETRY, 1_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("x"));
    harness.execution.onUnknown(
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "outcome unknown"));

    ModelInvocation model = ThreadProcessorTestSupport.model(harness.store, harness.invocationId);
    assertEquals(ModelInvocationStatus.UNKNOWN, model.status());
    assertNull(model.result());
    harness.nano.assertExhausted();
  }

  /** 成功计时必须经 durable ModelInvocation.result 完整带到 ThreadProcessor 物化的 ASSISTANT metadata。 */
  @Test
  void successDurationReachesMaterializedAssistantMetadata() {
    Harness harness = new Harness(NO_RETRY, 1_000_000_000L, 1_750_000_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("hello"));
    harness.execution.onSucceeded(new ProviderCompletion(response("hello")));
    assertEquals(750L, persistedDuration(harness));

    harness.threadFixture.nextClaim(harness.turn.threadId());

    EntryPath path = ThreadProcessorTestSupport.path(harness.store, harness.turn.threadId());
    MessagePayload assistant =
        path.entries().stream()
            .map(Entry::payload)
            .filter(MessagePayload.class::isInstance)
            .map(MessagePayload.class::cast)
            .filter(payload -> payload.assistantMetadata() != null)
            .findFirst()
            .orElseThrow();
    assertEquals(750L, assistant.assistantMetadata().decodeDurationMillis());
  }

  /** 计时严格非负：即使时间源异常回退使窗口为负也只落 0，绝不写负值。 */
  @Test
  void nonMonotonicObservationClampsToZero() {
    Harness harness = new Harness(NO_RETRY, 10_000_000L, 5_000L);

    harness.execution.onEvent(new ProviderStreamEvent.TextDelta("x"));
    harness.execution.onSucceeded(new ProviderCompletion(response("x")));

    assertEquals(0L, persistedDuration(harness));
    harness.nano.assertExhausted();
  }

  /** 读取已持久化的成功结果计时；断言终态为 SUCCEEDED，计时本身可为 null（无可信流计时）。 */
  private Long persistedDuration(Harness harness) {
    ModelInvocation model = ThreadProcessorTestSupport.model(harness.store, harness.invocationId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, model.status());
    return model.result().decodeDurationMillis();
  }

  /** 每个测试一个进程内 Model execution：READY -> DISPATCHING -> activate 到 RUNNING，claim 未过期。 */
  private final class Harness {

    final ThreadProcessorTestSupport.Fixture threadFixture =
        new ThreadProcessorTestSupport.Fixture();
    final InMemoryHarnessStore store = threadFixture.store;
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final ScriptedNanoTime nano;
    final ThreadProcessorTestSupport.OpenTurnBaseline turn;
    final UUID invocationId;
    final ModelExecution execution;

    Harness(InvocationRetryPolicy retryPolicy, Long... nanoValues) {
      executorsToClose.add(scheduler);
      this.nano = new ScriptedNanoTime(nanoValues);
      this.turn = ThreadProcessorTestSupport.seedOpenInputTurn(store);
      this.invocationId =
          store.transaction(
              tx -> {
                tx.lockThread(turn.threadId());
                UUID id = tx.nextId();
                tx.insertModelInvocation(
                    new ModelInvocation(
                        id,
                        turn.threadId(),
                        turn.turnStartEntryId(),
                        turn.userEntryId(),
                        ThreadProcessorTestSupport.plainRequest(),
                        ModelInvocationStatus.READY,
                        0,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        NOW,
                        NOW));
                tx.requestWork(new WorkTarget(WorkTargetType.MODEL, id), NOW);
                return id;
              });
      ClaimedWork claim =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.MODEL, NOW, "token-" + invocationId, NOW.plusSeconds(60)))
              .orElseThrow();
      // 模拟 ModelProcessor prepare：READY -> DISPATCHING。
      store.transaction(
          tx -> {
            ModelInvocation model = tx.lockModelInvocation(invocationId).orElseThrow();
            tx.updateModelInvocation(model.beginDispatch(NOW));
            return null;
          });
      this.execution =
          new ModelExecution(
              store,
              event -> {},
              claim,
              turn.threadId(),
              1,
              false,
              ThreadProcessorTestSupport.plainRequest().toolBindings(),
              new ModelProcessorConfig(
                  ThreadProcessorTestSupport.LEASE_CONFIG,
                  () -> retryPolicy,
                  FALLBACK_DELAY,
                  FLUSH_CONFIG),
              Clock.fixed(NOW, ZoneOffset.UTC),
              scheduler,
              Runnable::run,
              Runnable::run,
              ignored -> {},
              nano);
      assertTrue(execution.startHeartbeat(), "heartbeat must start");
      assertEquals(ProcessResult.STARTED, execution.activate(new FakeHandle()));
    }
  }

  /** 脚本化单调纳秒源：按调用顺序出栈，未消费或超额消费在断言处暴露。 */
  private static final class ScriptedNanoTime implements LongSupplier {

    private final ArrayDeque<Long> values = new ArrayDeque<>();

    ScriptedNanoTime(Long... values) {
      for (Long value : values) {
        this.values.add(value);
      }
    }

    @Override
    public long getAsLong() {
      Long value = values.poll();
      if (value == null) {
        throw new AssertionError("unexpected nanoTime call");
      }
      return value;
    }

    void assertExhausted() {
      if (!values.isEmpty()) {
        throw new AssertionError("unconsumed nanoTime values: " + values);
      }
    }
  }

  private static final class FakeHandle implements ModelGateway.Handle {

    @Override
    public void cancel() {}

    @Override
    public void activate() {}
  }

  private static ProviderResponse response(String text) {
    return new ProviderResponse(
        text, "", List.of(), GenerationStopReason.COMPLETE, USAGE, COST, "req-1", null, "{}");
  }

  private static ProviderResponse toolResponse() {
    return toolResponse("");
  }

  private static ProviderResponse toolResponse(String text) {
    return new ProviderResponse(
        text,
        "",
        List.of(new ProviderToolCall("call-1", "bash", "{\"q\":1}")),
        GenerationStopReason.COMPLETE,
        USAGE,
        COST,
        "req-1",
        null,
        "{}");
  }
}
