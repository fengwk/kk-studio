package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** {@link ToolExecutionGateway#start}：统一单路径执行、requirements 校验、admission 分类与两阶段门控测试。 */
class ToolExecutionGatewayStartTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  @Test
  void hostRouteExecutesWithExactRequestAndContext() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(tool), transport, store, executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(request),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      startedResult.handle().activate();
      awaitSize(tool.requests, 1);
      ToolExecutionRequest executed = tool.requests.get(0);
      assertEquals(DESCRIPTOR, executed.descriptor());
      assertSame(request.call(), executed.call());
      assertEquals(Duration.ZERO, executed.timeout());
      assertEquals(ToolGatewayTestSupport.INVOCATION_ID, executed.context().invocationId());
      assertEquals(ToolGatewayTestSupport.THREAD_ID, executed.context().threadId());
      assertTrue(transport.invocations.isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void environmentRouteUsesFrozenEnvironmentIdOnlyAndSameDisplayNamesAreIrrelevant() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);
      ToolInvocationRequest requestA =
          ToolGatewayTestSupport.environmentRequest("call-a", ToolGatewayTestSupport.ENV_A);
      ToolInvocationRequest requestB =
          ToolGatewayTestSupport.environmentRequest("call-b", ToolGatewayTestSupport.ENV_B);
      ToolGateway.StartResult startedA =
          gateway.start(
              ToolGatewayTestSupport.execution(requestA),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.StartResult startedB =
          gateway.start(
              ToolGatewayTestSupport.execution(requestB),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResultA = assertInstanceOf(ToolGateway.Started.class, startedA);
      ToolGateway.Started startedResultB = assertInstanceOf(ToolGateway.Started.class, startedB);
      startedResultA.handle().activate();
      startedResultB.handle().activate();
      awaitSize(transport.invocations, 2);
      assertEquals(ToolGatewayTestSupport.ENV_A, transport.invocations.get(0).environmentId());
      assertEquals(ToolGatewayTestSupport.ENV_B, transport.invocations.get(1).environmentId());
      assertEquals(
          ToolGatewayTestSupport.INVOCATION_ID.toString(),
          transport.invocations.get(0).request().call().id());
      assertEquals(
          requestA.call().argumentsJson(),
          transport.invocations.get(0).request().call().argumentsJson());
      assertEquals(
          EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
          transport.invocations.get(0).request().descriptor());
      assertEquals(Duration.ZERO, transport.invocations.get(0).request().timeout());
      assertTrue(
          transport.invocations.get(0).request().call().argumentsJson().contains("workdir"),
          transport.invocations.get(0).request().call().argumentsJson());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void missingHostToolIsRejected() {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_NOT_FOUND", rejected.error().kind());
  }

  @Test
  void hostDefinitionMismatchIsRejected() {
    ToolDescriptor drifted =
        new ToolDescriptor(
            DESCRIPTOR.name(),
            DESCRIPTOR.version(),
            "drifted description",
            DESCRIPTOR.rendererKey(),
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(ToolGatewayTestSupport.hostRequest("call-1", drifted)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());
  }

  @Test
  void missingEnvironmentCapabilityIsRejected() {
    ToolDescriptor unknown =
        new ToolDescriptor(
            "no_such_daemon_tool",
            "1",
            "not in the daemon catalog",
            "no_such_daemon_tool",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    AgentToolDefinition unknownDefinition =
        new AgentToolDefinition(
            new AgentToolId("test.no-such-daemon-tool"), unknown, ToolVisibility.SELECTABLE);
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "no_such_daemon_tool", "{}"),
            new ToolBinding(
                unknownDefinition,
                new ContributorBinding("test", "missing", List.of()),
                true,
                ToolGatewayTestSupport.ENV_A));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(request),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_NOT_FOUND", rejected.error().kind());
  }

  @Test
  void environmentCapabilityMismatchIsRejected() {
    ToolDescriptor driftedBash =
        new ToolDescriptor(
            "bash",
            "1",
            "drifted bash description",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolContribution bashContribution =
        ToolGatewayTestSupport.defaultCatalog().findTool(BuiltinToolIds.BASH).orElseThrow();
    AgentToolDefinition driftedDefinition =
        new AgentToolDefinition(
            bashContribution.definition().id(),
            driftedBash,
            bashContribution.definition().visibility());
    ContributorBinding contributor =
        new ContributorBinding(
            bashContribution.id().contributorId().value(),
            bashContribution.id().localName(),
            List.of());
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "bash", "{}"),
            new ToolBinding(driftedDefinition, contributor, true, ToolGatewayTestSupport.ENV_A));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(request),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());
  }

  @Test
  void hostToolDescriptorMutationOrNullAfterFreezeIsRejectedBeforeExecute() {
    AtomicReference<ToolDescriptor> liveDescriptor = new AtomicReference<>(DESCRIPTOR);
    Tool tool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return liveDescriptor.get();
          }

          @Override
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            throw new AssertionError("execute must not be reached when descriptor drifted");
          }
        };
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());

    liveDescriptor.set(hostDriftedDescriptor("2"));
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());

    liveDescriptor.set(null);
    ToolGateway.StartResult nullResult =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected nullRejected = assertInstanceOf(ToolGateway.Rejected.class, nullResult);
    assertEquals("TOOL_DEFINITION_MISMATCH", nullRejected.error().kind());
  }

  @Test
  void hostContributorBindingProvenanceMismatchIsRejected() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());

    ToolBinding mismatchedContributorBinding =
        new ToolBinding(
            new AgentToolDefinition(
                ToolGatewayTestSupport.TEST_TOOL_ID, DESCRIPTOR, ToolVisibility.SELECTABLE),
            new ContributorBinding("wrong-contributor", "host-tool", List.of()),
            false,
            null);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                new ToolInvocationRequest(
                    new ToolCall("call-1", DESCRIPTOR.name(), "{}"), mismatchedContributorBinding)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());
    assertTrue(rejected.error().message().contains("Frozen contributor binding"));
  }

  @Test
  void environmentContributorBindingProvenanceMismatchIsRejected() {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());

    ToolContribution bashContribution =
        ToolGatewayTestSupport.defaultCatalog().findTool(BuiltinToolIds.BASH).orElseThrow();
    ToolBinding mismatchedBinding =
        new ToolBinding(
            bashContribution.definition(),
            new ContributorBinding("wrong-contributor", "environment.bash", List.of()),
            true,
            ToolGatewayTestSupport.ENV_A);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                new ToolInvocationRequest(
                    new ToolCall(
                        "call-1", "bash", "{\"command\":\"ls\",\"workdir\":\"/home/dev\"}"),
                    mismatchedBinding)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());
    assertTrue(rejected.error().message().contains("Frozen contributor binding"));
  }

  @Test
  void environmentToolRequiresEnvironmentId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolGatewayTestSupport.environmentRequest("call-1", null));
  }

  @Test
  void environmentUnavailableIsDeterministicRejectionVisibleToModel() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE;
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started started = assertInstanceOf(ToolGateway.Started.class, result);
    started.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void environmentBusyIsDeliveredAsRetryableFailure() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_BUSY;
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started started = assertInstanceOf(ToolGateway.Started.class, result);
    started.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void environmentUncertainSendIsIndeterminate() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNCERTAIN;
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started started = assertInstanceOf(ToolGateway.Started.class, result);
    started.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("REMOTE_UNCERTAIN", unknown.error().kind());
  }

  @Test
  void environmentInvalidRequestIsUnknown() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_INVALID;
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started started = assertInstanceOf(ToolGateway.Started.class, result);
    started.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
  }

  @Test
  void localExecutorOverloadIsOverloadedWithWholeMillisecondDelay() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    executor.rejectSubmissions = true;
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.RetryLater overloaded = assertInstanceOf(ToolGateway.RetryLater.class, result);
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), overloaded.retryAfter());
    assertEquals(overloaded.retryAfter().toMillis(), overloaded.retryAfter().toNanos() / 1_000_000);
  }

  @Test
  void syncToolCallbackIsGatedUntilStartCommitted() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) ->
            listener.onComplete(ToolGatewayTestSupport.result(request.call().id(), "done"));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty());
    startedResult.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    assertEquals(
        "call-1",
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result()
            .toolCallId());
  }

  @Test
  void environmentSyncCallbackIsBufferedAndReplayedOnActivation() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty());
    startedResult.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    assertEquals(
        "call-1",
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result()
            .toolCallId());
  }

  @Test
  void catalogDriftedDescriptorIsRejectedAsMismatch() {
    ToolDescriptor drifted = hostDriftedDescriptor("2");
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("test"), "Test", "1", Set.of()),
            registrar ->
                registrar.registerTool(
                    "host-tool",
                    ToolGatewayTestSupport.TEST_TOOL_ID,
                    new ToolGatewayTestSupport.FakeTool(drifted),
                    ToolVisibility.SELECTABLE,
                    0));
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            catalog,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor());
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("TOOL_DEFINITION_MISMATCH", rejected.error().kind());
  }

  @Test
  void hostExecutorAmbiguousSubmissionIsIndeterminate() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    executor.ambiguousException = new IllegalStateException("executor broken");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(new ToolGatewayTestSupport.FakeTool(DESCRIPTOR)),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    ToolGateway.Indeterminate indeterminate =
        assertInstanceOf(ToolGateway.Indeterminate.class, result);
    assertEquals("EXECUTION_FAILED", indeterminate.error().kind());
    assertEquals("executor broken", indeterminate.error().message());
  }

  @Test
  void cancelBeforeEnvironmentActivationDropsBufferedCallbacks() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().cancel();
    startedResult.handle().activate();
    executor.drain();
    assertTrue(listener.events.isEmpty());
  }

  @Test
  void nullEnvironmentTransportHandleIsPostAcceptanceUnknown() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void synchronousTerminalWinsOverNullEnvironmentTransportHandle() {
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "sync");
    transport.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals("call-1", succeeded.result().toolCallId());
  }

  private static ToolDescriptor hostDriftedDescriptor(String version) {
    return new ToolDescriptor(
        DESCRIPTOR.name(),
        version,
        "drifted description",
        DESCRIPTOR.rendererKey(),
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  private static void awaitSize(List<?> list, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (list.size() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, list.size());
  }
}
