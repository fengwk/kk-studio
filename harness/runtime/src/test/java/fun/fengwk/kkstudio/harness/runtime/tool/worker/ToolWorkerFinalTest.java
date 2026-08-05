package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.BeforeToolCallResult;
import fun.fengwk.kkstudio.harness.runtime.tool.PermissionBoundaryInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class ToolWorkerFinalTest {
  private static RemoteToolTransport noopTransport() {
    return (environmentName, request, listener) -> {
      throw new RemoteToolUnavailableException("no remote transport in platform fixture");
    };
  }

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final Path WORKDIR = Path.of("/work").toAbsolutePath();
  private static final Path ENV_ROOT = Path.of("/").toAbsolutePath();

  /** 测试用 Environment 的 canonical 路由身份；RemoteTool/Transport 只接受 canonical EnvironmentId。 */
  private static final EnvironmentId ENV_A =
      new EnvironmentId("123e4567-e89b-12d3-a456-426614174000");

  private static final EnvironmentId ENV_B =
      new EnvironmentId("223e4567-e89b-12d3-a456-426614174000");
  private static final EnvironmentId ENV_C =
      new EnvironmentId("323e4567-e89b-12d3-a456-426614174000");

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void shutdownSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  @Test
  void validatesPositiveInvocationId() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    assertThrows(IllegalArgumentException.class, () -> fixture.worker.dispatch(0));
  }

  @Test
  void environmentUnavailableBeforeSendConvergesToTerminalFailed() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RemoteToolUnavailableException(environmentName + " offline");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("ENVIRONMENT_UNAVAILABLE", fixture.transactions.error.kind());
    assertEquals(0, fixture.transactions.retryCalls);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertEquals(0, fixture.tool.executions);
  }

  @Test
  void dueEnvironmentRetryWaitUnavailabilityConvergesToTerminalFailed() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = retryWaiting(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RemoteToolUnavailableException(environmentName + " offline");
        });

    assertTrue(fixture.transactions.candidate.isDispatchableAt(NOW));
    assertTrue(fixture.worker.dispatch(fixture.transactions.candidate.id()));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("ENVIRONMENT_UNAVAILABLE", fixture.transactions.error.kind());
    assertEquals(0, fixture.transactions.retryCalls);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertEquals(0, fixture.tool.executions);
  }

  @Test
  void environmentUncertainSendCompletesUnknownImmediately() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RemoteToolSendUncertainException("maybe delivered");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("REMOTE_UNCERTAIN", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
  }

  @Test
  void asyncUncertainDisconnectCompletesUnknownWithoutRetryEvenWhenIdempotent() {
    Fixture fixture = fixture(ToolSideEffect.IDEMPOTENT);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            3, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    assertNotNull(remoteListener.get(), "transport listener must be set during dispatch");
    remoteListener
        .get()
        .onError(new RemoteToolSendUncertainException("connection lost mid-flight"));

    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("REMOTE_UNCERTAIN", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
  }

  @Test
  void environmentSlotReleasedWhenClaimedExecutionThrowsUnexpectedly() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new IllegalStateException("transport exploded");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
  }

  @Test
  void environmentRemoteCompleteExternalizesInlineBinaryAsResourceLazily() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    remoteListener
        .get()
        .onComplete(
            new ToolResult(
                "call-1",
                List.of(new BinaryToolContent("text/plain", new byte[] {9, 9})),
                false,
                "{}",
                false));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals(
        "[binary output stored as resource]",
        ((TextToolContent) fixture.transactions.result.contents().get(0)).text());
    assertTrue(fixture.transactions.result.contents().get(1) instanceof ResourceToolContent);
    assertArrayEquals(new byte[] {9, 9}, fixture.resources.content);
  }

  @Test
  void partialWithResourceContentFailsTerminalWithoutRealtimeOrResources() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(
        new ToolResult(
            "call-1",
            List.of(
                new ResourceToolContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            false,
            "{}",
            false));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("EXECUTION_FAILED", fixture.transactions.error.kind());
    assertTrue(fixture.realtimeEvents.isEmpty());
    assertNull(fixture.resources.content);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void terminalResourceContentPassesThroughUnchangedWithoutExternalization() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    ResourceRef resource =
        new ResourceRef("https://example.com/a", "text/plain", "a.txt", null, null);

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(
        new ToolResult("call-1", List.of(new ResourceToolContent(resource)), false, "{}", false));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals(1, fixture.transactions.result.contents().size());
    ResourceToolContent persisted =
        (ResourceToolContent) fixture.transactions.result.contents().get(0);
    assertEquals(resource, persisted.resource());
    // 已有规范 Resource 引用直接透传，ResourceStore 不被触碰。
    assertNull(fixture.resources.content);
  }

  @Test
  void constructorProcessesTerminalCompletion() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    schedulers.add(scheduler);
    ToolWorker worker =
        new ToolWorker(
            fixture.transactions,
            (name, version) -> fixture.registryTool.map(value -> (Tool) value),
            noopTransport(),
            fixture.interceptorChain,
            fixture.resources,
            () -> fixture.retryPolicy,
            fixture.realtimeEvents::add,
            ToolWorkerConfig.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            Runnable::run,
            () -> "worker-token",
            () -> ToolSettings.DEFAULT,
            WORKDIR,
            ENV_ROOT);

    assertTrue(worker.dispatch(1));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(worker.hasActiveExecution());
  }

  @Test
  void dispatchesFrozenPlatformToolAndCommitsSuccessBeforeThreadActivation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution());
    assertEquals(1L, fixture.tool.request.context().invocationId());
    assertEquals(2L, fixture.tool.request.context().threadId());
    assertEquals("{}", fixture.tool.request.call().argumentsJson());

    fixture.tool.listener.onComplete(result("done"));

    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals("done", text(fixture.transactions.result));
    assertFalse(fixture.tool.handle.cancelled);
  }

  @Test
  void publishesPartialAsLossyRealtimeProjectionAndRecordsDurableActivity() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial"));

    assertEquals(1, fixture.realtimeEvents.size());
    RealtimeEvent.ToolPartial partial = (RealtimeEvent.ToolPartial) fixture.realtimeEvents.get(0);
    assertEquals(2L, partial.threadId());
    assertEquals(1L, partial.toolInvocationId());
    assertEquals(1, partial.attempt());
    assertEquals("partial", text(partial.partial()));
    assertEquals(List.of(NOW), fixture.transactions.activities);

    fixture.tool.listener.onComplete(result("done"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
  }

  @Test
  void lostPartialFenceSuppressesRealtimeAndNeverPersistsPartialResources() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.activityOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial-large-output"));

    assertTrue(fixture.realtimeEvents.isEmpty());
    assertNull(fixture.resources.content);
    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void isolatesRealtimeProjectionFailuresFromDurableSuccess() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.realtimeFailure = true;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial"));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
  }

  @Test
  void recoveredLeaseBecomesUnknownWithoutReplayingAnyToolSideEffect() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.recoveredLease = true;

    assertTrue(fixture.worker.dispatch(1));

    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertEquals(
        "Tool ownership was lost; execution result is unknown.",
        fixture.transactions.error.message());
  }

  @Test
  void missingOrDriftedFrozenDescriptorFailsWithoutExecution() {
    Fixture missing = fixture(ToolSideEffect.READ_ONLY);
    missing.registryTool = Optional.empty();
    missing.rebuildWorker();
    assertTrue(missing.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, missing.transactions.terminalStatus);
    assertEquals(0, missing.tool.executions);

    Fixture drifted = fixture(ToolSideEffect.READ_ONLY);
    drifted.registryTool = Optional.of(new RecordingTool(descriptor(ToolSideEffect.IDEMPOTENT)));
    drifted.rebuildWorker();
    assertTrue(drifted.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, drifted.transactions.terminalStatus);
    assertEquals(0, drifted.registryTool.orElseThrow().executions);
  }

  @Test
  void idempotentFailureReschedulesTargetWithoutDispatchingAndCancelsLocalHandle() {
    Fixture fixture = fixture(ToolSideEffect.IDEMPOTENT);
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            1, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(2), Duration.ofSeconds(2));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onError(new IllegalStateException("temporary"));

    assertEquals(1, fixture.transactions.retryCalls);
    assertEquals(NOW.plusSeconds(2), fixture.transactions.nextAttemptAt);
    assertNull(fixture.transactions.terminalStatus);
    assertTrue(fixture.tool.handle.cancelled);
  }

  @Test
  void nonIdempotentFailureNeverRetriesAndWakesOwningThread() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            3, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onError(new IllegalStateException("side effect uncertain"));

    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("side effect uncertain", fixture.transactions.error.message());
  }

  @Test
  void lostTerminalOwnershipCancelsLocalHandleAndPublishesNothing() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.terminalOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("late"));

    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void lostTerminalOwnershipDoesNotRunAfterInterceptorOrPersistResources() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    int[] afterCalls = {0};
    fixture.interceptorChain =
        new ToolInterceptorChain(
            List.of(),
            List.of(
                context -> {
                  afterCalls[0]++;
                  return context.result();
                }));
    fixture.transactions.terminalOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("large-output"));

    assertEquals(0, afterCalls[0]);
    assertNull(fixture.resources.content);
    assertTrue(fixture.tool.handle.cancelled);
  }

  @Test
  void afterInterceptorFailureIsPersistedAsFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    AfterToolCallInterceptor failing =
        context -> {
          throw new IllegalStateException("after failed");
        };
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of(failing));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("AFTER_INTERCEPTOR_FAILED", fixture.transactions.error.kind());
    assertTrue(fixture.transactions.error.message().contains("after failed"));
    assertFalse(fixture.tool.handle.cancelled);
  }

  @Test
  void resourcePersistenceFailureBecomesDeterministicFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.resources.failure = new IllegalStateException("resource store unavailable");
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("large-output"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("RESULT_PERSISTENCE_FAILED", fixture.transactions.error.kind());
    assertEquals("resource store unavailable", fixture.transactions.error.message());
  }

  @Test
  void externalizesLargeOutputWithUtf8SafePreview() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            5));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("😀😀"));

    assertEquals("😀\n[full output stored as resource]", text(fixture.transactions.result));
    assertTrue(fixture.transactions.result.contents().get(1) instanceof ResourceToolContent);
    assertEquals("😀😀", new String(fixture.resources.content, StandardCharsets.UTF_8));
  }

  @Test
  void heartbeatOwnershipLossCancelsLocalHandleWithoutForgingTerminalState() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.renewOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1),
            Duration.ofMillis(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertNull(fixture.transactions.terminalStatus);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (fixture.worker.hasActiveExecution() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void localConflictAbandonsStaleHandleAndUnknownsNewClaimWithoutRerunningTool() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    assertTrue(fixture.worker.dispatch(1));
    int executionsAfterFirst = fixture.tool.executions;

    // Simulate reclaim of the same invocation while the stale local handle is still mapped.
    fixture.transactions.recoveredLease = false;
    fixture.transactions.forceLocalConflict = true;
    assertTrue(fixture.worker.dispatch(1));

    assertEquals(executionsAfterFirst, fixture.tool.executions);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LOCAL_CONFLICT", fixture.transactions.error.kind());
    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void dispatchReturnsBeforeExternalToolExecutionOnCallerThread() throws Exception {
    // Deferred executor proves dispatch() must NOT call Tool.execute on the caller thread.
    DeferredExecutor deferred = new DeferredExecutor();
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorkerWithExecutor(deferred);

    // Caller thread (test thread) - dispatch must return without invoking external Tool.execute.
    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(0, fixture.tool.executions);
    assertFalse(fixture.worker.hasActiveExecution());
    assertTrue(deferred.hasPending());

    // Drive the executor; Tool.execute must now run on the executor, not the caller.
    deferred.runPending();
    assertEquals(1, fixture.tool.executions);
    assertTrue(fixture.worker.hasActiveExecution());

    // Complete the tool call; existing terminal path remains correct under async launch.
    fixture.tool.listener.onComplete(result("done"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void submissionRejectionConvergesToTerminalFailed() {
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("test executor is shut down");
        };
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorkerWithExecutor(rejecting);

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("EXECUTION_REJECTED", fixture.transactions.error.kind());
    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(0, fixture.tool.executions);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void submissionRejectionOnRetryWaitConvergesToTerminalFailed() {
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("test executor is shut down");
        };
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = retryWaiting(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithExecutor(rejecting);

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("EXECUTION_REJECTED", fixture.transactions.error.kind());
    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(0, fixture.tool.executions);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void stopInvalidatesPendingDispatchBeforeExecutorRuns() {
    DeferredExecutor deferred = new DeferredExecutor();
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorkerWithExecutor(deferred);

    // dispatch registers a ticket and submits a deferred Runnable that has not yet executed.
    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(0, fixture.tool.executions);
    assertTrue(deferred.hasPending());
    assertFalse(fixture.worker.hasActiveExecution());

    // stop() must invalidate the pending ticket before any active execution is abandoned, so the
    // deferred Runnable becomes a no-op instead of invoking external Tool I/O.
    fixture.worker.stop();

    // Now drive the deferred executor; the dispatched task must do nothing.
    deferred.runPending();

    // No external tool execution occurred.
    assertEquals(0, fixture.tool.executions);
    // No terminal persistence (no completeSuccess / completeFailure / completeUnknown /
    // scheduleRetry calls beyond the initial claim).
    assertNull(fixture.transactions.terminalStatus);
    assertEquals(0, fixture.transactions.retryCalls);
    // The execution map is gated only on actual handles, never on pending tickets.
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void admittedDeferredDispatchReleasesTicketBeforeNextDispatch() {
    // After admission, the conditional-remove gate releases the transient fence, so a later
    // dispatch for the same invocation id must be able to admit its own ticket without being
    // fenced out by an orphaned previous entry.
    DeferredExecutor deferred = new DeferredExecutor();
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorkerWithExecutor(deferred);

    // First dispatch admits its ticket, runs, completes, and releases the fence.
    assertTrue(fixture.worker.dispatch(1));
    deferred.runPending();
    fixture.tool.listener.onComplete(result("first"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(1, fixture.tool.executions);

    // Second dispatch for the same invocation id must run a fresh tool.execute — an orphaned
    // ticket from the first dispatch must not fence out the second admission.
    assertTrue(fixture.worker.dispatch(1));
    deferred.runPending();
    assertEquals(2, fixture.tool.executions);
    fixture.tool.listener.onComplete(result("second"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  // ---- durable Tool permission gate ----

  @Test
  void pendingPermissionAllowPersistsBeforeAnyToolExecute() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.interceptorChain =
        chainWithBoundary(new AllowingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));

    // The chain's ALLOW must have been persisted BEFORE Tool.execute is allowed to run.
    assertEquals(1, fixture.transactions.persistedAllowCalls);
    assertEquals(0, fixture.transactions.awaitCalls);
    assertEquals(0, fixture.transactions.denyCalls);
    assertEquals(1, fixture.tool.executions);
    assertTrue(fixture.worker.hasActiveExecution());

    fixture.tool.listener.onComplete(result("done"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
  }

  @Test
  void pendingPermissionDenyExecutesZeroToolsAndLeavesNoLocalHandle() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.interceptorChain =
        chainWithBoundary(new DenyingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(0, fixture.tool.executions);
    assertEquals(0, fixture.transactions.persistedAllowCalls);
    assertEquals(0, fixture.transactions.awaitCalls);
    assertEquals(1, fixture.transactions.denyCalls);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void pendingPermissionAskExecutesZeroToolsAndLeavesNoLocalHandle() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.interceptorChain =
        chainWithBoundary(new AskingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(0, fixture.tool.executions);
    assertEquals(0, fixture.transactions.persistedAllowCalls);
    assertEquals(1, fixture.transactions.awaitCalls);
    assertEquals(0, fixture.transactions.denyCalls);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void allowedBypassesPermissionEvaluatorAndExecutesPersistedPlan() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate =
        allowed(descriptor(ToolSideEffect.READ_ONLY), null, "worker-token");
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    // An empty chain with no permission boundary must still work: the ALLOWED branch must skip
    // the chain entirely.
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(0, fixture.transactions.persistedAllowCalls);
    assertEquals(0, fixture.transactions.awaitCalls);
    assertEquals(0, fixture.transactions.denyCalls);
    assertEquals(1, fixture.tool.executions);
    fixture.tool.listener.onComplete(result("done"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
  }

  @Test
  void askedReachingDispatchedWorkerThrowsInvariant() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    // Force claim to return a WAITING/ASKED row directly: the dispatcher precondition already
    // filters this, so we synthesize the breach to prove the worker throws.
    fixture.transactions.candidate = waiting(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimAsked = true;
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    fixture.rebuildWorker();

    // dispatch() must surface IllegalStateException; no Tool.execute, no terminal write.
    assertThrows(IllegalStateException.class, () -> fixture.worker.dispatch(1));
    assertEquals(0, fixture.tool.executions);
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void postAllowExecutionFailureCompletesFailedAllowed() {
    // After persistPermissionAllowed the row is RUNNING/ALLOWED. An external Tool failure must
    // converge to FAILED/ALLOWED (the schema/aggregate explicitly allow this).
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate =
        allowed(descriptor(ToolSideEffect.READ_ONLY), null, "worker-token");
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.tool.executions);
    fixture.tool.listener.onError(new RuntimeException("execution blew up"));
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
  }

  // ---- explicit Environment Tool lifecycle coverage ----

  @Test
  void remoteCancellationReleasesEnvironmentSlotAndPersistsCancelled() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution(ENV_A));
    assertTrue(fixture.worker.hasActiveExecution());

    remoteListener.get().onError(new RemoteToolCancelledException("daemon asked to cancel"));

    assertEquals(InvocationStatus.CANCELLED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertFalse(fixture.worker.hasActiveExecution());
    // Cancellation must not be retried even when the descriptor is idempotent.
    assertEquals(0, fixture.transactions.retryCalls);
  }

  @Test
  void environmentSlotLifecycleAdmitsFreshDispatchAfterTerminal() {
    // Each in-flight ENVIRONMENT execution occupies exactly one environment slot via
    // hasActiveExecution(name). Once the execution reaches a terminal state the slot is released
    // and a new dispatch (different environment, different invocation id) is admitted without
    // any leaked slot from the previous run. The gateway's "one active per environment" rule is
    // enforced at the transport layer; here we prove the ToolWorker's process-local map releases
    // the slot deterministically across completion, failure, and disconnect paths.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    AtomicReference<ToolExecutionListener> listenerA = new AtomicReference<>();
    AtomicReference<ToolExecutionListener> listenerB = new AtomicReference<>();
    AtomicReference<ToolExecutionListener> listenerC = new AtomicReference<>();
    AtomicInteger pending = new AtomicInteger();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          pending.incrementAndGet();
          if (ENV_A.equals(environmentName)) {
            listenerA.set(listener);
          } else if (ENV_B.equals(environmentName)) {
            listenerB.set(listener);
          } else if (ENV_C.equals(environmentName)) {
            listenerC.set(listener);
          } else {
            throw new IllegalStateException("unexpected environment " + environmentName);
          }
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    // 1) Dispatch env-a; the slot is held while the daemon run is in-flight.
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    assertTrue(fixture.worker.dispatch(101));
    assertTrue(fixture.worker.hasActiveExecution(ENV_A));
    assertTrue(fixture.worker.hasActiveExecution());

    // 2) Completion releases the env-a slot and clears the global hasActiveExecution gate.
    listenerA.get().onComplete(result("done-a"));
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertFalse(fixture.worker.hasActiveExecution());

    // 3) After terminal convergence the slot is reusable: env-b is admitted independently.
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_B);
    fixture.transactions.descriptor = environmentDescriptor();
    assertTrue(fixture.worker.dispatch(102));
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertTrue(fixture.worker.hasActiveExecution(ENV_B));

    // 4) Failure releases the env-b slot exactly like completion.
    listenerB.get().onError(new RuntimeException("env-b blew up"));
    assertFalse(fixture.worker.hasActiveExecution(ENV_B));
    assertFalse(fixture.worker.hasActiveExecution());

    // 5) Disconnect (uncertain send) releases the env-c slot exactly like completion.
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_C);
    fixture.transactions.descriptor = environmentDescriptor();
    assertTrue(fixture.worker.dispatch(103));
    assertTrue(fixture.worker.hasActiveExecution(ENV_C));
    listenerC.get().onError(new RemoteToolSendUncertainException("env-c dropped"));
    assertFalse(fixture.worker.hasActiveExecution(ENV_C));
    assertFalse(fixture.worker.hasActiveExecution());

    // The transport has been invoked exactly three times (one per dispatched invocation) and each
    // listener was recorded.
    assertEquals(3, pending.get());
    assertNotNull(listenerA.get());
    assertNotNull(listenerB.get());
    assertNotNull(listenerC.get());
  }

  @Test
  void lostOwnershipTerminalWriteDoesNotInvokeToolExecute() {
    // When the durable permission ALLOW write is rejected (e.g. the row's lease expired between
    // claim and persistPermissionAllowed), no Tool.execute may run and the row converges to
    // terminal FAILED with PERMISSION_BOUNDARY_MISSING. This proves the worker fails closed on
    // ownership loss during the post-claim permission transition.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.transactions.allowOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.interceptorChain =
        chainWithBoundary(new AllowingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.persistedAllowCalls);
    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("PERMISSION_BOUNDARY_MISSING", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void lostOwnershipAwaitPermissionDoesNotInvokeToolExecute() {
    // Mirror of the ALLOW lost-ownership test for the ASK branch: durable state must converge
    // without any external Tool I/O when the awaitPermission write is rejected.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.transactions.askOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.interceptorChain =
        chainWithBoundary(new AskingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.awaitCalls);
    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("PERMISSION_BOUNDARY_MISSING", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void lostOwnershipDenyPermissionDoesNotInvokeToolExecute() {
    // Mirror of the ASK/ALLOW lost-ownership tests for the DENY branch.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = pending(descriptor(ToolSideEffect.READ_ONLY), null);
    fixture.transactions.descriptor = descriptor(ToolSideEffect.READ_ONLY);
    fixture.transactions.claimPending = true;
    fixture.transactions.denyOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.interceptorChain =
        chainWithBoundary(new DenyingBoundary(new PermissionPromptPreview("tool", "/work", "{}")));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.denyCalls);
    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("PERMISSION_BOUNDARY_MISSING", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void deadlineExceededBeforeExecutionConvergesToFailedWithoutToolSideEffect() {
    // A claim whose durable deadline is already in the past must converge to FAILED without ever
    // constructing an ExecutionCallback or invoking external Tool I/O. The process-local slot
    // stays empty so the FIFO head is durable-only.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.pastDeadline = true;
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("TIMEOUT", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void recoveredLeaseDoesNotInvokeToolAndPersistsUnknown() {
    // Belt-and-suspenders companion to the existing recoveredLease test: prove that even when
    // the durable recoveredLease flag is set, no external Tool I/O runs and the slot is empty.
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.recoveredLease = true;

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution());
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
  }

  @Test
  void externalDisconnectDuringRemoteRunConvergesToUnknownAndReleasesSlot() {
    // The async onError path with a RemoteToolSendUncertainException is the production
    // disconnect signal: the active slot must be released and the row must converge to UNKNOWN
    // (never FAILED, never retried) so a later FIFO sibling can be admitted by the gateway.
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            5, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution(ENV_A));
    remoteListener.get().onError(new RemoteToolSendUncertainException("socket closed mid-invoke"));

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("REMOTE_UNCERTAIN", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(0, fixture.transactions.retryCalls);
  }

  @Test
  void repeatedDispatchOfSameInvocationAdmitsFreshExecutionAfterTerminal() {
    // After an invocation reaches a terminal state, dispatching it again (e.g. after durable
    // retry/re-dispatch) must construct a fresh ExecutionCallback and run a fresh tool.execute;
    // the previous terminal convergence must not leak the process-local slot or short-circuit
    // the next attempt.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> firstListener = new AtomicReference<>();
    AtomicReference<ToolExecutionListener> secondListener = new AtomicReference<>();
    AtomicInteger dispatched = new AtomicInteger();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          if (dispatched.incrementAndGet() == 1) {
            firstListener.set(listener);
          } else {
            secondListener.set(listener);
          }
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    firstListener.get().onComplete(result("first"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution(ENV_A));
    secondListener.get().onComplete(result("second"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution(ENV_A));
    assertEquals(2, dispatched.get());
    assertEquals(2, fixture.transactions.claimCalls);
  }

  // ---- scheduler-stop and cleanup regression coverage ----

  @Test
  void schedulerTasksStopAfterSuccessfulTerminalCompletion() throws Exception {
    // After onComplete persists SUCCEEDED, the heartbeat / timeout / partial-flush scheduler
    // futures must all be cancelled so the worker does not keep renewing the lease (which is now
    // already terminal) and does not keep flushing partial batches. We pick a heartbeat
    // interval short enough that at least one tick would fire during the test wait if the
    // scheduler were still armed, then assert renewCalls did not grow after the wait.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(2, TimeUnit.SECONDS));
    int renewsBeforeTerminal = fixture.transactions.renewCalls;
    assertTrue(renewsBeforeTerminal >= 1);

    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());

    // Wait long enough for several heartbeat ticks to have fired if the scheduler were still
    // armed. The wait upper-bounds the duration deterministically.
    Thread.sleep(150);
    assertEquals(
        renewsBeforeTerminal,
        fixture.transactions.renewCalls,
        "heartbeat must stop firing after terminal convergence");
  }

  @Test
  void schedulerTasksStopAfterHeartbeatOwnershipLoss() throws Exception {
    // When renew returns LOST_OWNERSHIP the heartbeat path forces terminal convergence; the
    // remaining heartbeat ticks must not keep firing and re-driving the owner slot removal.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.renewOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(2, TimeUnit.SECONDS));
    int renewsAtTerminal = fixture.transactions.renewCalls;
    assertTrue(renewsAtTerminal >= 1);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (fixture.worker.hasActiveExecution() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertFalse(fixture.worker.hasActiveExecution());
    assertNull(fixture.transactions.terminalStatus);
    assertEquals(
        0,
        fixture.transactions.terminalMutationCalls,
        "ownership loss must fence callbacks instead of forging a terminal write");

    Thread.sleep(150);
    assertEquals(
        renewsAtTerminal,
        fixture.transactions.renewCalls,
        "heartbeat must stop firing after LOST_OWNERSHIP forces terminal convergence");
  }

  @Test
  void schedulerTasksStopAfterRecordActivityOwnershipLoss() throws Exception {
    // When recordActivity returns LOST_OWNERSHIP the partial-flush path forces terminal
    // convergence; subsequent flush ticks must not keep firing and re-driving the owner slot
    // removal.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.activityOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofMillis(20),
            Duration.ofSeconds(1),
            1,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    // Send a partial result; onPartial will accumulate and the partial-flush task will trigger
    // recordActivity which returns LOST_OWNERSHIP and forces terminal convergence.
    fixture.tool.listener.onPartial(result("partial-trigger"));
    assertTrue(fixture.transactions.recordActivityed.await(2, TimeUnit.SECONDS));
    int recordActivitiesAtTerminal = fixture.transactions.recordActivityCalls;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (fixture.worker.hasActiveExecution() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertFalse(fixture.worker.hasActiveExecution());
    assertNull(fixture.transactions.terminalStatus);
    assertEquals(
        0,
        fixture.transactions.terminalMutationCalls,
        "recordActivity ownership loss must not be followed by a terminal write");

    Thread.sleep(150);
    assertEquals(
        recordActivitiesAtTerminal,
        fixture.transactions.recordActivityCalls,
        "partial-flush must stop firing after LOST_OWNERSHIP forces terminal convergence");
  }

  @Test
  void schedulerTasksStopAfterCancellation() throws Exception {
    // A RemoteToolCancelledException from the daemon must converge to terminal CANCELLED and
    // tear down all three scheduler futures. We pick a heartbeat interval short enough that a
    // tick would fire if the schedulers were still armed.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(2, TimeUnit.SECONDS));
    int renewsAtCancel = fixture.transactions.renewCalls;

    fixture.tool.listener.onError(new RemoteToolCancelledException("daemon cancelled"));

    assertEquals(InvocationStatus.CANCELLED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());

    Thread.sleep(150);
    assertEquals(
        renewsAtCancel,
        fixture.transactions.renewCalls,
        "heartbeat must stop firing after onError(RemoteToolCancelledException)");
  }

  @Test
  void schedulerTasksStopAfterUnknownFromAsyncDisconnect() throws Exception {
    // An async RemoteToolSendUncertainException must converge to terminal UNKNOWN and tear down
    // all three scheduler futures; otherwise a later heartbeat tick would re-try renew on a
    // row the durable store already considers terminal.
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(2, TimeUnit.SECONDS));
    int renewsAtUnknown = fixture.transactions.renewCalls;

    fixture.tool.listener.onError(new RemoteToolSendUncertainException("connection lost"));

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());

    Thread.sleep(150);
    assertEquals(
        renewsAtUnknown,
        fixture.transactions.renewCalls,
        "heartbeat must stop firing after onError(RemoteToolSendUncertainException)");
  }

  @Test
  void terminalPersistenceExceptionCannotLeakActiveMapOrSchedulers() throws Exception {
    // If the durable SUCCEEDED write throws (simulating a database outage), the worker must
    // fail closed: the process-local slot is removed, the underlying handle is cancelled,
    // schedulers are torn down, and the heartbeat does not continue to fire.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.throwOnCompleteSuccess = true;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(2, TimeUnit.SECONDS));
    int renewsBeforeComplete = fixture.transactions.renewCalls;

    fixture.tool.listener.onComplete(result("done"));

    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("RESULT_PERSISTENCE_FAILED", fixture.transactions.error.kind());

    Thread.sleep(150);
    assertEquals(
        renewsBeforeComplete,
        fixture.transactions.renewCalls,
        "heartbeat must stop firing when terminal persistence throws");
  }

  @Test
  void synchronousToolExecuteExceptionReleasesActiveMapAndSchedulers() throws Exception {
    // The synchronous RuntimeException path in dispatchClaimed must not leak the execution slot
    // or any scheduler even when onError aborts. The transport is configured to throw
    // synchronously from invoke so the catch (RuntimeException) branch is exercised; the active
    // map must be empty and the heartbeat must not have been driven to fire.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), ENV_A);
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.transactions.terminalOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RuntimeException("transport exploded before any handle was returned");
        });
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertFalse(fixture.worker.hasActiveExecution());

    Thread.sleep(150);
    assertEquals(
        0,
        fixture.transactions.renewCalls,
        "synchronous error path must not start a heartbeat task");
  }

  @Test
  void terminalGateSerializesCompetingCallbacksAndReleasesOwnerOnce() throws Exception {
    // Hold the first callback inside its durable success write. A competing onError must observe
    // TERMINATING rather than independently schedule a failure/retry mutation. This direct
    // callback fixture also observes the conditional owner-release consumer exactly once.
    ToolDescriptor descriptor = descriptor(ToolSideEffect.READ_ONLY);
    RecordingTransactions transactions =
        new RecordingTransactions(queued(descriptor, null), descriptor);
    transactions.completeSuccessEntered = new CountDownLatch(1);
    transactions.releaseCompleteSuccess = new CountDownLatch(1);
    ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    schedulers.add(scheduler);
    AtomicInteger ownerReleases = new AtomicInteger();
    ToolWorkerConfig config =
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024);
    ClaimedToolInvocation claimed =
        new ClaimedToolInvocation(
            running(descriptor, "worker-token"), InvocationStatus.QUEUED, false);
    ExecutionCallback callback =
        new ExecutionCallback(
            claimed,
            ToolBinding.of(descriptor),
            new ToolCall("call-1", descriptor.name(), "{}"),
            transactions,
            () ->
                new InvocationRetryPolicy(
                    0,
                    InvocationRetryBackoffStrategy.FIXED,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)),
            new TerminalCompleter(
                transactions,
                new ToolInterceptorChain(List.of(), List.of()),
                new MemoryResources(),
                config,
                Clock.fixed(NOW, ZoneOffset.UTC)),
            ignored -> {},
            config,
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            ignored -> ownerReleases.incrementAndGet());
    callback.schedule();

    Thread complete = new Thread(() -> callback.onComplete(result("success")));
    complete.start();
    assertTrue(transactions.completeSuccessEntered.await(2, TimeUnit.SECONDS));
    int renewsAfterTerminalOwnership = transactions.renewCalls;

    Thread error =
        new Thread(
            () ->
                callback.onError(new IllegalStateException("competing callback must be ignored")));
    error.start();
    error.join(TimeUnit.SECONDS.toMillis(1));
    assertFalse(error.isAlive(), "competing terminal callback must return without durable work");

    transactions.releaseCompleteSuccess.countDown();
    complete.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(
        complete.isAlive(), "terminal owner must complete after its durable write is released");
    assertEquals(1, transactions.terminalMutationCalls);
    assertEquals(0, transactions.retryCalls);
    assertEquals(1, ownerReleases.get());

    Thread.sleep(150);
    assertEquals(
        renewsAfterTerminalOwnership,
        transactions.renewCalls,
        "scheduler work must stop as soon as terminal ownership is acquired");
  }

  @Test
  void terminalGatePreventsConcurrentCallbackFromLeakingWorkerSlot() throws Exception {
    // Repeat the success-vs-error race through ToolWorker so the conditional owner release is
    // verified against the real active-execution map, not only a direct callback consumer.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.completeSuccessEntered = new CountDownLatch(1);
    fixture.transactions.releaseCompleteSuccess = new CountDownLatch(1);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofMillis(20),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    Thread complete = new Thread(() -> fixture.tool.listener.onComplete(result("success")));
    complete.start();
    assertTrue(fixture.transactions.completeSuccessEntered.await(2, TimeUnit.SECONDS));
    int renewsAfterTerminalOwnership = fixture.transactions.renewCalls;

    Thread error =
        new Thread(
            () ->
                fixture.tool.listener.onError(
                    new IllegalStateException("competing callback must be ignored")));
    error.start();
    error.join(TimeUnit.SECONDS.toMillis(1));
    assertFalse(error.isAlive(), "competing callback must not wait on the terminal transaction");

    fixture.transactions.releaseCompleteSuccess.countDown();
    complete.join(TimeUnit.SECONDS.toMillis(2));
    assertFalse(complete.isAlive());
    assertEquals(1, fixture.transactions.terminalMutationCalls);
    assertFalse(fixture.worker.hasActiveExecution(), "terminal cleanup must remove the owner slot");

    Thread.sleep(150);
    assertEquals(
        renewsAfterTerminalOwnership,
        fixture.transactions.renewCalls,
        "no heartbeat may continue after the terminal owner acquires the gate");
  }

  @Test
  void terminalOwnerFlushesBufferedPartialExactlyOnce() throws Exception {
    // A partial below the normal batch threshold remains buffered. onComplete must acquire the
    // terminal gate first, then drain and persist that partial exactly once even though scheduled
    // flushes are now rejected in TERMINATING.
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("buffered"));
    assertEquals(0, fixture.transactions.recordActivityCalls);

    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals(1, fixture.transactions.recordActivityCalls);
    assertEquals(1, fixture.transactions.activities.size());
    assertEquals(1, fixture.realtimeEvents.size());
    assertTrue(fixture.realtimeEvents.get(0) instanceof RealtimeEvent.ToolPartial);
    assertFalse(fixture.worker.hasActiveExecution());

    Thread.sleep(150);
    assertEquals(
        1,
        fixture.transactions.recordActivityCalls,
        "terminal-owned partial drain must run once and scheduled flushes must stay cancelled");
  }

  private static ToolInterceptorChain chainWithBoundary(PermissionBoundaryInterceptor boundary) {
    return new ToolInterceptorChain(List.of(boundary), List.of());
  }

  private static ToolInvocation pending(ToolDescriptor descriptor, EnvironmentId environmentId) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease("worker-token", NOW.plusSeconds(10)),
        NOW.plusSeconds(30),
        NOW,
        null,
        null,
        null,
        NOW.minusSeconds(1),
        NOW,
        null,
        ToolPermissionState.PENDING,
        false);
  }

  private static ToolInvocation allowed(
      ToolDescriptor descriptor, EnvironmentId environmentId, String token) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(token, NOW.plusSeconds(10)),
        NOW.plusSeconds(30),
        NOW,
        null,
        null,
        null,
        NOW.minusSeconds(1),
        NOW,
        null,
        ToolPermissionState.ALLOWED,
        false);
  }

  private static ToolInvocation waiting(ToolDescriptor descriptor, EnvironmentId environmentId) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.WAITING_INTERACTION,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        null,
        null,
        ToolPermissionState.ASKED,
        false);
  }

  private static final class AllowingBoundary implements PermissionBoundaryInterceptor {
    private final PermissionPromptPreview preview;

    AllowingBoundary(PermissionPromptPreview preview) {
      this.preview = preview;
    }

    @Override
    public BeforeToolCallResult intercept(BeforeToolCallContext context) {
      return new BeforeToolCallResult(
          context.binding(), context.call().argumentsJson(), PermissionAction.ALLOW, preview);
    }
  }

  private static final class DenyingBoundary implements PermissionBoundaryInterceptor {
    private final PermissionPromptPreview preview;

    DenyingBoundary(PermissionPromptPreview preview) {
      this.preview = preview;
    }

    @Override
    public BeforeToolCallResult intercept(BeforeToolCallContext context) {
      return new BeforeToolCallResult(
          context.binding(), context.call().argumentsJson(), PermissionAction.DENY, preview);
    }
  }

  private static final class AskingBoundary implements PermissionBoundaryInterceptor {
    private final PermissionPromptPreview preview;

    AskingBoundary(PermissionPromptPreview preview) {
      this.preview = preview;
    }

    @Override
    public BeforeToolCallResult intercept(BeforeToolCallContext context) {
      return new BeforeToolCallResult(
          context.binding(), context.call().argumentsJson(), PermissionAction.ASK, preview);
    }
  }

  private Fixture fixture(ToolSideEffect sideEffect) {
    Fixture fixture = new Fixture(sideEffect);
    fixture.rebuildWorker();
    return fixture;
  }

  private static ToolResult result(String text) {
    return new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}", false);
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static ToolDescriptor descriptor(ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        "tool",
        "1",
        ToolType.PLATFORM,
        "test tool",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        sideEffect,
        Duration.ofSeconds(30));
  }

  private static ToolDescriptor environmentDescriptor() {
    return new ToolDescriptor(
        "environmentTool",
        "1",
        ToolType.ENVIRONMENT,
        "environment tool",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ToolInvocation queued(ToolDescriptor descriptor, EnvironmentId environmentId) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.QUEUED,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        null,
        null,
        ToolPermissionState.PENDING,
        false);
  }

  private static ToolInvocation retryWaiting(
      ToolDescriptor descriptor, EnvironmentId environmentId) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.RETRY_WAIT,
        2,
        NOW,
        null,
        NOW.plusSeconds(30),
        NOW.minusSeconds(1),
        null,
        null,
        null,
        NOW.minusSeconds(2),
        NOW.minusSeconds(1),
        null,
        ToolPermissionState.ALLOWED,
        false);
  }

  private static ToolInvocation running(ToolDescriptor descriptor, String token) {
    return running(descriptor, token, descriptor.name().startsWith("environment") ? ENV_A : null);
  }

  private static ToolInvocation running(
      ToolDescriptor descriptor, String token, EnvironmentId environmentId) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(token, NOW.plusSeconds(10)),
        NOW.plusSeconds(30),
        NOW,
        null,
        null,
        null,
        NOW.minusSeconds(1),
        NOW,
        null,
        ToolPermissionState.ALLOWED,
        false);
  }

  private static ToolInvocation runningWithPastDeadline(ToolDescriptor descriptor, String token) {
    return runningWithPastDeadline(
        descriptor, token, descriptor.name().startsWith("environment") ? ENV_A : null);
  }

  private static ToolInvocation runningWithPastDeadline(
      ToolDescriptor descriptor, String token, EnvironmentId environmentId) {
    Instant createdAt = NOW.minusSeconds(10);
    Instant startedAt = NOW.minusSeconds(5);
    Instant pastDeadline = NOW.minusSeconds(3);
    Instant lastActivityAt = NOW.minusSeconds(4);
    Instant leaseUntil = NOW.minusSeconds(1);
    return new ToolInvocation(
        1L,
        2L,
        3L,
        4L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentId,
        7L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(token, leaseUntil),
        pastDeadline,
        lastActivityAt,
        null,
        null,
        null,
        createdAt,
        startedAt,
        null,
        ToolPermissionState.ALLOWED,
        false);
  }

  private final class Fixture {
    private final RecordingTransactions transactions;
    private final RecordingTool tool;
    private final MemoryResources resources = new MemoryResources();
    private final List<RealtimeEvent> realtimeEvents = new ArrayList<>();

    private ToolInterceptorChain interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    private Optional<RecordingTool> registryTool;
    private InvocationRetryPolicy retryPolicy =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    private boolean realtimeFailure;
    private ToolWorker worker;
    private RemoteToolTransport transport = noopTransport();
    private Executor dispatchExecutor = Runnable::run;

    private Fixture(ToolSideEffect sideEffect) {
      ToolDescriptor descriptor = descriptor(sideEffect);
      transactions = new RecordingTransactions(queued(descriptor, null), descriptor);
      tool = new RecordingTool(descriptor);
      registryTool = Optional.of(tool);
    }

    private void rebuildWorker() {
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorkerWithTransport(RemoteToolTransport transport) {
      this.transport = transport;
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorkerWithExecutor(Executor executor) {
      this.dispatchExecutor = executor;
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorker(ToolWorkerConfig config) {
      ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
      schedulers.add(scheduler);
      worker =
          new ToolWorker(
              transactions,
              (name, version) -> registryTool.map(value -> (Tool) value),
              transport,
              interceptorChain,
              resources,
              () -> retryPolicy,
              event -> {
                if (realtimeFailure) {
                  throw new IllegalStateException("redis unavailable");
                }
                realtimeEvents.add(event);
              },
              config,
              Clock.fixed(NOW, ZoneOffset.UTC),
              scheduler,
              dispatchExecutor,
              () -> "worker-token",
              () -> ToolSettings.DEFAULT,
              WORKDIR,
              ENV_ROOT);
    }
  }

  private static final class RecordingTransactions implements ToolInvocationTransactions {
    private ToolInvocation candidate;
    private ToolDescriptor descriptor;
    private boolean recoveredLease;
    private boolean forceLocalConflict;
    private boolean claimPending;
    private boolean claimAsked;
    private int claimCalls;
    private ToolInvocationUpdateOutcome renewOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome activityOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome terminalOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome retryOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome allowOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome askOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome denyOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private boolean pastDeadline;
    private boolean throwOnCompleteSuccess;
    private CountDownLatch completeSuccessEntered;
    private CountDownLatch releaseCompleteSuccess;
    private final CountDownLatch renewed = new CountDownLatch(1);
    private final CountDownLatch recordActivityed = new CountDownLatch(1);
    private final List<Instant> activities = new ArrayList<>();
    private int renewCalls;
    private int recordActivityCalls;
    private int retryCalls;
    private int terminalMutationCalls;
    private int persistedAllowCalls;
    private int awaitCalls;
    private int denyCalls;
    private Instant nextAttemptAt;
    private InvocationStatus terminalStatus;
    private ToolResult result;
    private ToolInvocationError error;
    private ToolBinding lastAllowBinding;
    private String lastAllowArguments;
    private ToolBinding lastAwaitBinding;
    private String lastAwaitArguments;
    private PermissionPromptPreview lastAwaitPrompt;

    private RecordingTransactions(ToolInvocation candidate, ToolDescriptor descriptor) {
      this.candidate = candidate;
      this.descriptor = descriptor;
    }

    @Override
    public Optional<ClaimedToolInvocation> claim(
        long invocationId, String workerToken, Duration workerLeaseDuration, Instant now) {
      claimCalls++;
      assertEquals("worker-token", workerToken);
      assertTrue(workerLeaseDuration.isPositive());
      boolean recovered = recoveredLease || forceLocalConflict;
      // forceLocalConflict still claims with recovered=false so dispatchClaimed hits putIfAbsent.
      if (forceLocalConflict) {
        recovered = false;
      }
      ToolInvocation claimed;
      EnvironmentId environmentId = candidate.environmentId();
      if (claimAsked) {
        claimed = waiting(descriptor, environmentId);
      } else if (claimPending) {
        claimed = pending(descriptor, environmentId);
      } else {
        claimed =
            pastDeadline
                ? runningWithPastDeadline(descriptor, workerToken, environmentId)
                : running(descriptor, workerToken, environmentId);
      }
      return Optional.of(new ClaimedToolInvocation(claimed, candidate.status(), recovered));
    }

    @Override
    public ToolInvocationUpdateOutcome renew(
        ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
      renewCalls++;
      renewed.countDown();
      return renewOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome recordActivity(
        ClaimedToolInvocation claimed, Instant activityAt, Instant now) {
      activities.add(activityAt);
      recordActivityCalls++;
      recordActivityed.countDown();
      return activityOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeSuccess(
        ClaimedToolInvocation claimed,
        Supplier<ToolResult> resultSupplier,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalMutationCalls++;
      blockCompleteSuccessIfConfigured();
      if (throwOnCompleteSuccess) {
        throw new RuntimeException("simulated terminal persistence failure");
      }
      if (terminalOutcome != ToolInvocationUpdateOutcome.APPLIED) {
        return terminalOutcome;
      }
      terminalStatus = InvocationStatus.SUCCEEDED;
      this.result = resultSupplier.get();
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeFailure(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalMutationCalls++;
      terminalStatus = InvocationStatus.FAILED;
      this.error = error;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeCancelled(
        ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now) {
      terminalMutationCalls++;
      terminalStatus = InvocationStatus.CANCELLED;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeUnknown(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalMutationCalls++;
      terminalStatus = InvocationStatus.UNKNOWN;
      this.error = error;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome scheduleRetry(
        ClaimedToolInvocation claimed,
        Instant nextAttemptAt,
        Instant lastObservedActivityAt,
        Instant now) {
      retryCalls++;
      terminalMutationCalls++;
      this.nextAttemptAt = nextAttemptAt;
      return retryOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome persistPermissionAllowed(
        ClaimedToolInvocation claimed,
        ToolBinding finalBinding,
        String finalArgumentsJson,
        Instant now) {
      persistedAllowCalls++;
      lastAllowBinding = finalBinding;
      lastAllowArguments = finalArgumentsJson;
      return allowOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome awaitPermission(
        ClaimedToolInvocation claimed,
        ToolBinding finalBinding,
        String finalArgumentsJson,
        PermissionPromptPreview prompt,
        Instant now) {
      awaitCalls++;
      lastAwaitBinding = finalBinding;
      lastAwaitArguments = finalArgumentsJson;
      lastAwaitPrompt = prompt;
      return askOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome denyPermission(ClaimedToolInvocation claimed, Instant now) {
      denyCalls++;
      return denyOutcome;
    }

    private void blockCompleteSuccessIfConfigured() {
      if (completeSuccessEntered == null || releaseCompleteSuccess == null) {
        return;
      }
      completeSuccessEntered.countDown();
      try {
        if (!releaseCompleteSuccess.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release completeSuccess");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting to release completeSuccess", error);
      }
    }
  }

  private static final class RecordingTool implements Tool {
    private final ToolDescriptor descriptor;
    private final Handle handle = new Handle();
    private ToolExecutionRequest request;
    private ToolExecutionListener listener;
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
      return handle;
    }
  }

  private static final class Handle implements ToolExecutionHandle {
    private final CountDownLatch cancelledLatch = new CountDownLatch(1);
    private volatile boolean cancelled;

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

  private static final class MemoryResources implements ResourceStore {
    private byte[] content;
    private RuntimeException failure;

    @Override
    public ResourceRef put(String mediaType, String name, byte[] content) {
      if (failure != null) {
        throw failure;
      }
      this.content = content.clone();
      return new ResourceRef(
          "https://example.test/resource", mediaType, name, (long) content.length, null);
    }

    @Override
    public byte[] read(ResourceRef resource) {
      if (content == null) {
        throw new IllegalStateException("no stored resource");
      }
      return content.clone();
    }
  }

  /**
   * Executor that captures submitted Runnables without executing them, for async-dispatch tests.
   */
  private static final class DeferredExecutor implements Executor {
    private final List<Runnable> pending = new ArrayList<>();

    @Override
    public synchronized void execute(Runnable command) {
      pending.add(command);
    }

    synchronized boolean hasPending() {
      return !pending.isEmpty();
    }

    synchronized void runPending() {
      List<Runnable> snapshot = new ArrayList<>(pending);
      pending.clear();
      for (Runnable runnable : snapshot) {
        runnable.run();
      }
    }
  }
}
