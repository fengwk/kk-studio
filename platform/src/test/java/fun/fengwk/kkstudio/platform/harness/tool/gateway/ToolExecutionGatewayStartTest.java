package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
      // host 工具没有 arguments 级超时契约：解析结果就是 definition 默认值。
      assertEquals(DESCRIPTOR.defaultTimeout(), executed.timeout());
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
      // bash 未携带 timeout_seconds：解析结果就是 capability definition 的默认超时。
      assertEquals(
          EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC)
              .defaultTimeout(),
          transport.invocations.get(0).request().timeout());
      assertTrue(
          transport.invocations.get(0).request().call().argumentsJson().contains("workdir"),
          transport.invocations.get(0).request().call().argumentsJson());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 显式 timeout_seconds 严格覆盖 capability 默认超时：更短与更长都必须原样传到 transport，且解析只在 Gateway 发生一次。 */
  @Test
  void environmentRouteResolvesExplicitTimeoutOverridingDefault() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);

      // 默认：bash 未携带 timeout_seconds，解析结果为 capability definition 默认值。
      ToolInvocationRequest defaulted =
          ToolGatewayTestSupport.environmentRequest(
              "call-default",
              ToolGatewayTestSupport.ENV_A,
              "{\"command\":\"ls\",\"workdir\":\"/home/dev\"}");
      // 显式更短
      ToolInvocationRequest shorter =
          ToolGatewayTestSupport.environmentRequest(
              "call-short",
              ToolGatewayTestSupport.ENV_A,
              "{\"command\":\"ls\",\"workdir\":\"/home/dev\",\"timeout_seconds\":7}");
      // 显式更长（7200 秒）：不得被默认值截断，也不存在产品上限
      ToolInvocationRequest longer =
          ToolGatewayTestSupport.environmentRequest(
              "call-long",
              ToolGatewayTestSupport.ENV_A,
              "{\"command\":\"ls\",\"workdir\":\"/home/dev\",\"timeout_seconds\":7200}");

      activate(gateway, defaulted);
      activate(gateway, shorter);
      activate(gateway, longer);
      awaitSize(transport.invocations, 3);

      Duration capabilityDefault =
          EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC)
              .defaultTimeout();
      assertEquals(capabilityDefault, transport.invocations.get(0).request().timeout());
      assertEquals(Duration.ofSeconds(7), transport.invocations.get(1).request().timeout());
      assertEquals(Duration.ofSeconds(7200), transport.invocations.get(2).request().timeout());
    } finally {
      executor.shutdownNow();
    }
  }

  private static void activate(ToolExecutionGateway gateway, ToolInvocationRequest request) {
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(request),
            new ToolGatewayTestSupport.RecordingListener());
    assertInstanceOf(ToolGateway.Started.class, started).handle().activate();
  }

  /** 超时只在执行前解析一次：句柄 activate 之前解析已完成，且该精确 Duration 原样交给工具实现。 */
  @Test
  void hostRouteResolvesTimeoutExactlyOnceBeforeExecution() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.resolvedTimeout = Duration.ofSeconds(7200);
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);

    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            new ToolGatewayTestSupport.RecordingListener());
    assertInstanceOf(ToolGateway.Started.class, started);
    // 执行尚未开始（句柄未 activate），解析已经完成且只发生一次。
    assertTrue(tool.requests.isEmpty());
    assertEquals(1, tool.resolveTimeoutCalls.get());

    assertInstanceOf(ToolGateway.Started.class, started).handle().activate();
    executor.drain();
    assertEquals(1, tool.requests.size());
    assertEquals(Duration.ofSeconds(7200), tool.requests.get(0).timeout());
    // 执行阶段不再二次解析。
    assertEquals(1, tool.resolveTimeoutCalls.get());
  }

  /** 非正数的 arguments 级超时 fail closed：确定性 INVALID_REQUEST 拒绝，既不提交 executor 也不触碰 transport。 */
  @Test
  void nonPositiveExplicitTimeoutIsRejectedBeforeExecution() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    // 构造期已用探针任务校验过 executor，这里只比较拒绝前后的队列长度。
    int queuedBefore = executor.queued.size();

    for (String arguments :
        List.of(
            "{\"command\":\"ls\",\"workdir\":\"/home/dev\",\"timeout_seconds\":0}",
            "{\"command\":\"ls\",\"workdir\":\"/home/dev\",\"timeout_seconds\":-5}")) {
      ToolGateway.Rejected rejected =
          assertInstanceOf(
              ToolGateway.Rejected.class,
              gateway.start(
                  ToolGatewayTestSupport.execution(
                      ToolGatewayTestSupport.environmentRequest(
                          "call-invalid", ToolGatewayTestSupport.ENV_A, arguments)),
                  new ToolGatewayTestSupport.RecordingListener()));
      assertEquals(ToolExecutionGateway.INVALID_REQUEST_KIND, rejected.error().kind());
      assertTrue(
          rejected.error().message().contains("must be positive"), rejected.error().message());
    }
    assertEquals(queuedBefore, executor.queued.size());
    assertTrue(transport.invocations.isEmpty());
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
            "not in the daemon catalog",
            "no_such_daemon_tool",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    AgentToolDefinition unknownDefinition =
        new AgentToolDefinition(unknown, ToolVisibility.SELECTABLE);
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
            "drifted bash description",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolContribution bashContribution =
        ToolGatewayTestSupport.defaultCatalog().findTool("bash").orElseThrow();
    AgentToolDefinition driftedDefinition =
        new AgentToolDefinition(driftedBash, bashContribution.definition().visibility());
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
            new AgentToolDefinition(DESCRIPTOR, ToolVisibility.SELECTABLE),
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
        ToolGatewayTestSupport.defaultCatalog().findTool("bash").orElseThrow();
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

  /** 未选择 Environment 的 branch：start 必须在提交执行前以稳定 kind 拒绝，且不触碰 transport。 */
  @Test
  void unselectedEnvironmentToolIsRejectedAtStartWithoutExecuting() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);

    // 构造期安全探针会占用队列一个位置；start 拒绝不得再提交任何执行任务。
    int queuedBefore = executor.queued.size();

    ToolGateway.StartResult result =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.unselectedEnvironmentRequest("call-1")),
            new ToolGatewayTestSupport.RecordingListener());

    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals("ENVIRONMENT_NOT_SELECTED", rejected.error().kind());
    assertTrue(
        rejected.error().message().contains("select an Environment"), rejected.error().message());
    // 未向 Environment transport 发送任何调用，也没有新提交的执行任务或结果写入。
    assertTrue(transport.invocations.isEmpty());
    assertEquals(queuedBefore, executor.queued.size());
    assertTrue(store.puts.isEmpty());
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
