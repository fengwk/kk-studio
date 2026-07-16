package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CloudToolWorkerTest {
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void shutdownSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /** Worker identifiers are validated before any durable claim can be attempted. */
  @Test
  void rejectsBlankWorkerId() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));

    assertThrows(IllegalArgumentException.class, () -> fixture.worker.executeNext(" "));

    assertEquals(0, fixture.tool.executions);
  }

  /** Dispatch returns before callbacks and batches partials outside the Session result path. */
  @Test
  void dispatchesAsynchronouslyAndFlushesPartialsBeforeTerminal() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));

    assertTrue(fixture.worker.executeNext("worker-a").isPresent());
    assertNotNull(fixture.tool.listener);
    assertEquals(1L, fixture.tool.request.context().invocationId());
    assertEquals(2L, fixture.tool.request.context().runId());
    fixture.tool.listener.onPartial(result("partial"));
    fixture.tool.listener.onComplete(result("complete"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(1, fixture.transactions.started);
    assertEquals(List.of("partial"), texts(fixture.transactions.partials.get(0)));
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertEquals("complete", text(fixture.transactions.result));
    assertTrue(fixture.transactions.coordinations >= 1);
    assertFalse(fixture.tool.handle.cancelled);
    assertToolCompletion(fixture, ToolInvocationStatus.SUCCEEDED, null);
  }

  /** Process stop cancels only the local handle and leaves durable recovery to the lease. */
  @Test
  void stopsActiveExecutionWithoutForgingTerminalState() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));

    fixture.worker.executeNext("worker-a");
    assertTrue(fixture.worker.hasActiveExecution());

    fixture.worker.stop();

    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(0, fixture.transactions.terminateCalls);
  }

  /** 最终结果在 durable terminate 前经过 after hook，且 hook 收到冻结 Tool 的准确 binding/call。 */
  @Test
  void transformsSuccessfulFinalResultBeforePersistence() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    AtomicReference<String> observed = new AtomicReference<>();
    fixture.interceptorChain =
        new ToolInterceptorChain(
            List.of(),
            List.of(
                context -> {
                  observed.set(
                      context.binding().descriptor().name()
                          + "@"
                          + context.binding().descriptor().version()
                          + ":"
                          + context.call().argumentsJson());
                  return result("hooked");
                }));
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onPartial(result("partial"));
    fixture.tool.listener.onComplete(result("complete"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals("tool@1:{}", observed.get());
    assertEquals(List.of("partial"), texts(fixture.transactions.partials.get(0)));
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertEquals("hooked", text(fixture.transactions.result));
    assertEquals(1, fixture.transactions.terminateCalls);
    assertToolCompletion(fixture, ToolInvocationStatus.SUCCEEDED, null);
  }

  /** after hook 的抛错、null 与 toolCallId 篡改都必须吞并为一次 durable FAILED，而非逃出 callback。 */
  @Test
  void persistsAfterInterceptorContractFailures() throws Exception {
    assertAfterFailure(
        context -> {
          throw new IllegalStateException("boom");
        });
    assertAfterFailure(context -> null);
    assertAfterFailure(context -> ToolResult.error("different", "changed"));
  }

  /** 完成 callback 与并发 cancel 只尝试一次终态 CAS，cancel 已持久化时成功结果不能覆盖它。 */
  @Test
  void preservesSingleTerminalCasAcrossCompletionCancelRace() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    CountDownLatch hookEntered = new CountDownLatch(1);
    CountDownLatch releaseHook = new CountDownLatch(1);
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    fixture.interceptorChain =
        new ToolInterceptorChain(
            List.of(),
            List.of(
                context -> {
                  hookEntered.countDown();
                  await(releaseHook);
                  return context.result();
                }));
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofSeconds(1), 8192, 8192, 1024));
    fixture.worker.executeNext("worker-a");
    Thread callback =
        new Thread(
            () -> {
              try {
                fixture.tool.listener.onComplete(result("complete"));
              } catch (Throwable error) {
                callbackFailure.set(error);
              }
            });

    callback.start();
    assertTrue(hookEntered.await(1, TimeUnit.SECONDS));
    fixture.store.current = copyWithCancel(fixture.store.current, NOW);
    assertTrue(fixture.store.cancelRead.await(1, TimeUnit.SECONDS));
    fixture.transactions.terminalResult = false;
    releaseHook.countDown();
    callback.join(1000);

    assertFalse(callback.isAlive());
    assertNull(callbackFailure.get());
    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(1, fixture.transactions.terminateCalls);
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertTrue(fixture.tool.handle.cancelled);
    assertTrue(toolCompletions(fixture).isEmpty());
  }

  /**
   * A synchronous success before execute returns its handle must not be mistaken for cancellation.
   */
  @Test
  void doesNotCancelHandleWhenToolCompletesSynchronously() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.tool.completeSynchronously = true;

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertFalse(fixture.tool.handle.cancelled);
  }

  /**
   * A frozen name/version absent from the current registry fails without invoking any Tool side
   * effect.
   */
  @Test
  void failsMissingFrozenToolWithoutExecution() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.registry = (name, version) -> Optional.empty();
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(0, fixture.tool.executions);
    assertEquals(ToolInvocationStatus.FAILED, fixture.transactions.status);
    assertTrue(text(fixture.transactions.result).contains("unavailable"));
    assertToolCompletion(
        fixture, ToolInvocationStatus.FAILED, "Frozen tool tool@1 is unavailable.");
  }

  /** pre-execution terminal CAS 丢失时既不发布 observation，也不触发 terminal coordination。 */
  @Test
  void doesNotObserveLostPreExecutionTerminalCas() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.registry = (name, version) -> Optional.empty();
    fixture.transactions.terminalResult = false;
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(1, fixture.transactions.coordinations);
    assertTrue(toolCompletions(fixture).isEmpty());
  }

  /** observer RuntimeException 由真实 Worker dispatcher 隔离，不改变 terminal CAS 与 coordination。 */
  @Test
  void isolatesObserverFailureFromToolTermination() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.lifecycleObservers =
        new HarnessLifecycleObservers(
            List.of(
                observation -> {
                  throw new IllegalStateException("observer failed");
                },
                fixture.observations::add));
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(result("complete"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertEquals(2, fixture.transactions.coordinations);
    assertToolCompletion(fixture, ToolInvocationStatus.SUCCEEDED, null);
  }

  /** CAS=true 后 coordinate 抛错：Tool observation 已经发布，不能因 coordination 失败而漏发。 */
  @Test
  void publishesToolCompletionBeforeCoordinateThrows() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.transactions.coordinateFailure = true;
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");

    try {
      fixture.tool.listener.onComplete(result("complete"));
    } catch (IllegalStateException expected) {
      // coordinate 抛错会让 listener 回调传出；生产侧由 Spring 事务或 wrapper 隔离。
    }

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(ToolInvocationStatus.SUCCEEDED, fixture.transactions.status);
    assertTrue(fixture.transactions.coordinations >= 2);
    assertToolCompletion(fixture, ToolInvocationStatus.SUCCEEDED, null);
  }

  /** Reclaimed non-idempotent work becomes UNKNOWN instead of executing a second side effect. */
  @Test
  void marksReclaimedNonIdempotentInvocationUnknown() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT, NOW.plusSeconds(30));
    fixture.store.recovered = true;

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(0, fixture.tool.executions);
    assertEquals(ToolInvocationStatus.UNKNOWN, fixture.transactions.status);
    assertToolCompletion(
        fixture, ToolInvocationStatus.UNKNOWN, "non-idempotent invocation lease expired");
  }

  /** Deadline owns the terminal race and asks the best-effort in-memory handle to cancel. */
  @Test
  void timesOutAndCancelsHandleWithoutWaitingForToolCallback() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusMillis(40));

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(2, TimeUnit.SECONDS));
    assertEquals(ToolInvocationStatus.FAILED, fixture.transactions.status);
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelled);
    assertTrue(text(fixture.transactions.result).contains("deadline"));
  }

  /** Heartbeat ownership loss cancels the local handle before relinquishing its durable lease. */
  @Test
  void cancelsHandleWhenHeartbeatLosesOwnership() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofSeconds(1), 8192, 8192, 1024));
    fixture.store.heartbeatResult = false;

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.store.heartbeatFailure.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelled);
  }

  /**
   * A terminal durable state observed during heartbeat also cancels an untrusted local execution.
   */
  @Test
  void cancelsHandleWhenDurableInvocationBecomesTerminal() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofSeconds(1), 8192, 8192, 1024));
    fixture.store.current = copyWithStatus(fixture.store.current, ToolInvocationStatus.SUCCEEDED);

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.store.terminalRead.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelled);
  }

  /**
   * Failed partial journaling after terminal ownership was claimed always stops and cancels
   * locally.
   */
  @Test
  void cancelsHandleAndStopsWhenPartialFlushFailsDuringCompletion() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.transactions.partialFailure = true;

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onPartial(result("partial"));
    assertThrows(
        IllegalStateException.class, () -> fixture.tool.listener.onComplete(result("complete")));

    assertTrue(fixture.tool.handle.cancelled);
    assertEquals(1, fixture.transactions.coordinations);
  }

  /**
   * A cancellation already persisted before dispatch wins and prevents execute from being called.
   */
  @Test
  void persistsCancellationWithoutDispatchingTool() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.store.current = copyWithCancel(fixture.store.current, NOW);

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(0, fixture.tool.executions);
    assertEquals(ToolInvocationStatus.CANCELLED, fixture.transactions.status);
    assertToolCompletion(fixture, ToolInvocationStatus.CANCELLED, "Tool execution cancelled.");
  }

  /**
   * Late callbacks still reach the port but cannot change durable state after lease ownership loss.
   */
  @Test
  void lateCallbackCannotMutateAfterOwnershipLoss() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.transactions.terminalResult = false;

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(result("late"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(1, fixture.transactions.coordinations);
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelled);
    assertTrue(toolCompletions(fixture).isEmpty());
  }

  /** Environment invocations are never dispatched by the Cloud/Control worker. */
  @Test
  void rejectsNonCloudTargetBeforeToolExecution() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.store.current = copyWithEnvironmentTarget(fixture.store.current);

    fixture.worker.executeNext("worker-a");

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(0, fixture.tool.executions);
    assertEquals(ToolInvocationStatus.FAILED, fixture.transactions.status);
    assertToolCompletion(
        fixture, ToolInvocationStatus.FAILED, "Tool worker cannot execute target ENVIRONMENT.");
  }

  /** A Tool error callback is terminalized asynchronously with its diagnostic message. */
  @Test
  void terminalizesToolErrorCallback() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onError(new IllegalStateException("boom"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(ToolInvocationStatus.FAILED, fixture.transactions.status);
    assertEquals("boom", text(fixture.transactions.result));
    assertToolCompletion(fixture, ToolInvocationStatus.FAILED, "boom");
  }

  /** Large output retains a bounded semantic preview and a complete global artifact. */
  @Test
  void storesLargeOutputAsArtifactWithPreview() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(5), 10, 8, 4));

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(result("0123456789"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals("0123\n[full output stored as artifact]", text(fixture.transactions.result));
    assertTrue(fixture.transactions.result.contents().get(1) instanceof ArtifactToolContent);
    assertEquals("0123456789", new String(fixture.artifacts.content, StandardCharsets.UTF_8));
  }

  /** Empty Tool results get a durable text representation so ordinal coordination can advance. */
  @Test
  void normalizesEmptyTerminalResultWithoutCancellingSuccessfulHandle() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(new ToolResult("call-1", List.of(), false, "{}", false));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals("", text(fixture.transactions.result));
    assertFalse(fixture.tool.handle.cancelled);
  }

  /**
   * Byte-limited previews preserve complete UTF-8 code points rather than replacement characters.
   */
  @Test
  void storesCodePointSafeUtf8Preview() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(5), 10, 1, 5));

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(result("😀😀"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals("😀\n[full output stored as artifact]", text(fixture.transactions.result));
  }

  private Fixture fixture(ToolSideEffect sideEffect, Instant deadline) {
    Fixture fixture = new Fixture(sideEffect, deadline);
    fixture.rebuildWorker();
    return fixture;
  }

  private ToolResult result(String text) {
    return new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}", true);
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static List<String> texts(List<ToolResult> results) {
    return results.stream().map(CloudToolWorkerTest::text).toList();
  }

  private static void assertToolCompletion(
      Fixture fixture, ToolInvocationStatus status, String errorMessage) {
    List<ToolCompleted> completions = toolCompletions(fixture);
    assertEquals(1, completions.size());
    ToolCompleted completed = completions.get(0);
    assertEquals(1L, completed.invocationId());
    assertEquals(2L, completed.runId());
    assertEquals(status, completed.status());
    assertEquals(errorMessage, completed.error());
    assertEquals(fixture.transactions.terminalAt, completed.occurredAt());
  }

  private static List<ToolCompleted> toolCompletions(Fixture fixture) {
    return fixture.observations.stream()
        .filter(ToolCompleted.class::isInstance)
        .map(ToolCompleted.class::cast)
        .toList();
  }

  private void assertAfterFailure(AfterToolCallInterceptor interceptor) throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, NOW.plusSeconds(30));
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of(interceptor));
    fixture.rebuildWorker();

    fixture.worker.executeNext("worker-a");
    fixture.tool.listener.onComplete(result("complete"));

    assertTrue(fixture.transactions.terminal.await(1, TimeUnit.SECONDS));
    assertEquals(1, fixture.transactions.terminateCalls);
    assertEquals(ToolInvocationStatus.FAILED, fixture.transactions.status);
    assertTrue(fixture.transactions.result.error());
    assertTrue(text(fixture.transactions.result).contains("afterToolCall"));
    assertTrue(fixture.transactions.errorMessage.contains("afterToolCall"));
    assertTrue(fixture.tool.handle.cancelled);
    assertToolCompletion(fixture, ToolInvocationStatus.FAILED, fixture.transactions.errorMessage);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for test synchronization");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test synchronization interrupted", error);
    }
  }

  private ToolInvocation copyWithStatus(ToolInvocation source, ToolInvocationStatus status) {
    return new ToolInvocation(
        source.id(),
        source.runId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        source.targetType(),
        source.environmentId(),
        source.argumentsJson(),
        status,
        source.permissionAction(),
        source.permissionDecision(),
        source.deadlineAt(),
        source.leaseOwner(),
        source.leaseUntil(),
        source.cancelRequestedAt(),
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private ToolInvocation copyWithEnvironmentTarget(ToolInvocation source) {
    return new ToolInvocation(
        source.id(),
        source.runId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        ToolTargetType.ENVIRONMENT,
        7L,
        source.argumentsJson(),
        source.status(),
        source.permissionAction(),
        source.permissionDecision(),
        source.deadlineAt(),
        source.leaseOwner(),
        source.leaseUntil(),
        source.cancelRequestedAt(),
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private ToolInvocation copyWithCancel(ToolInvocation source, Instant cancelledAt) {
    return new ToolInvocation(
        source.id(),
        source.runId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        source.targetType(),
        source.environmentId(),
        source.argumentsJson(),
        source.status(),
        source.permissionAction(),
        source.permissionDecision(),
        source.deadlineAt(),
        source.leaseOwner(),
        source.leaseUntil(),
        cancelledAt,
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private final class Fixture {
    private final RecordingStore store;
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final RecordingTool tool;
    private final MemoryArtifacts artifacts = new MemoryArtifacts();
    private final List<HarnessLifecycleObservation> observations = new ArrayList<>();
    private ToolInterceptorChain interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    private HarnessLifecycleObservers lifecycleObservers =
        new HarnessLifecycleObservers(List.of(observations::add));
    private ToolRegistry registry;
    private CloudToolWorker worker;

    private Fixture(ToolSideEffect sideEffect, Instant deadline) {
      tool = new RecordingTool(descriptor(sideEffect));
      store = new RecordingStore(invocation(deadline, tool.descriptor()));
      registry = (name, version) -> Optional.of(tool);
    }

    private void rebuildWorker() {
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorker(ToolWorkerConfig config) {
      ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
      schedulers.add(scheduler);
      worker =
          new CloudToolWorker(
              store,
              transactions,
              registry,
              interceptorChain,
              artifacts,
              config,
              Clock.fixed(NOW, ZoneOffset.UTC),
              scheduler,
              lifecycleObservers);
    }
  }

  private static ToolInvocation invocation(Instant deadline, ToolDescriptor descriptor) {
    return new ToolInvocation(
        1,
        2,
        3,
        0,
        "call-1",
        descriptor.name(),
        descriptor.version(),
        ToolTargetType.CLOUD,
        null,
        "{}",
        ToolInvocationStatus.RUNNING,
        PermissionAction.ALLOW,
        null,
        deadline,
        "worker-a",
        deadline.plusSeconds(60),
        null,
        null,
        null,
        NOW,
        NOW,
        null,
        NOW);
  }

  private static ToolDescriptor descriptor(ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        "tool",
        "1",
        "test tool",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        ToolExecutionMode.CLOUD,
        sideEffect,
        Duration.ofSeconds(30));
  }

  private static final class RecordingStore implements ToolInvocationWorkerStore {
    private volatile ToolInvocation current;
    private boolean claimed;
    private boolean recovered;
    private volatile boolean heartbeatResult = true;
    private final CountDownLatch heartbeatFailure = new CountDownLatch(1);
    private final CountDownLatch terminalRead = new CountDownLatch(1);
    private final CountDownLatch cancelRead = new CountDownLatch(1);

    private RecordingStore(ToolInvocation current) {
      this.current = current;
    }

    @Override
    public Optional<ClaimedToolInvocation> claimDue(
        String owner, Instant now, Duration leaseDuration) {
      if (claimed) {
        return Optional.empty();
      }
      claimed = true;
      return Optional.of(new ClaimedToolInvocation(current, recovered));
    }

    @Override
    public boolean heartbeat(ClaimedToolInvocation claimed, Instant now, Duration leaseDuration) {
      if (!heartbeatResult) {
        heartbeatFailure.countDown();
      }
      return heartbeatResult;
    }

    @Override
    public Optional<ToolInvocation> find(long invocationId) {
      if (current.cancelRequestedAt() != null) {
        cancelRead.countDown();
      }
      if (current.status().isTerminal()) {
        terminalRead.countDown();
      }
      return Optional.of(current);
    }
  }

  private static final class RecordingTransactions implements ToolInvocationTransactions {
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final List<List<ToolResult>> partials = new ArrayList<>();
    private boolean terminalResult = true;
    private boolean partialFailure;
    private boolean coordinateFailure;
    private int started;
    private int coordinations;
    private int terminateCalls;
    private ToolInvocationStatus status;
    private ToolResult result;
    private String errorMessage;
    private Instant terminalAt;

    @Override
    public boolean start(ClaimedToolInvocation claimed, Instant now) {
      started++;
      return true;
    }

    @Override
    public boolean appendPartial(
        ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now) {
      if (partialFailure) {
        throw new IllegalStateException("partial journal unavailable");
      }
      this.partials.add(partials);
      return true;
    }

    @Override
    public boolean terminate(
        ClaimedToolInvocation claimed,
        ToolInvocationStatus status,
        ToolResult result,
        String errorMessage,
        Instant now) {
      terminateCalls++;
      this.status = status;
      this.result = result;
      this.errorMessage = errorMessage;
      terminalAt = now;
      terminal.countDown();
      return terminalResult;
    }

    @Override
    public int coordinateReadyRuns(Instant now) {
      coordinations++;
      if (coordinateFailure && coordinations >= 2) {
        throw new IllegalStateException("coordinate queue unavailable");
      }
      return 0;
    }
  }

  private static final class RecordingTool implements Tool {
    private final ToolDescriptor descriptor;
    private final Handle handle = new Handle();
    private ToolExecutionListener listener;
    private ToolExecutionRequest request;
    private boolean completeSynchronously;
    private int executions;

    private RecordingTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      executions++;
      this.request = request;
      this.listener = listener;
      if (completeSynchronously) {
        listener.onComplete(
            new ToolResult(
                request.call().id(), List.of(new TextToolContent("done")), false, "{}", false));
      }
      return handle;
    }
  }

  private static final class Handle implements ToolExecutionHandle {
    private boolean cancelled;
    private final CountDownLatch cancelledLatch = new CountDownLatch(1);

    @Override
    public void cancel() {
      cancelled = true;
      cancelledLatch.countDown();
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }

  private static final class MemoryArtifacts implements ArtifactStore {
    private byte[] content;

    @Override
    public ArtifactRef save(String mediaType, String encoding, byte[] content) {
      this.content = content.clone();
      return new ArtifactRef("8", mediaType, content.length);
    }

    @Override
    public Optional<Artifact> find(String artifactId) {
      return Optional.empty();
    }
  }
}
