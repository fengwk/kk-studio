package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.ACK;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.CANCELLED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.COMPLETED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.PARTIAL;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.coding.InMemoryResourceStore;
import fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Daemon 生命周期及本地 Tool SPI 的协议集成测试。 */
class DaemonRuntimeTest {

  private static final long ASYNC_TEST_TIMEOUT_SECONDS = 5;
  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("123e4567-e89b-12d3-a456-426614174000");

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
  private DaemonRuntime runtime;
  private FakeTransport handshakeTransport;

  @AfterEach
  void closeRuntime() {
    if (runtime != null) {
      runtime.close();
    }
  }

  /** 断线后必须重连并重新完成 HELLO/WELCOME/READY 的握手，使 daemon 在新连接上重新进入 READY 状态。 */
  @Test
  void reconnectsAfterDisconnectAndReannouncesReadiness() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    List<DaemonEnvelope> handshake = transport.takeMessages(2);
    assertMessageTypes(handshake, HELLO, READY);
    JsonNode hello = codec.readPayload(handshake.get(0));
    assertEquals("test-gateway-token", hello.path("gatewayToken").asText());
    assertEquals("daemon", hello.path("daemonId").asText());
    assertEquals(DaemonProtocol.VERSION_2, hello.path("protocolVersion").asInt());
    assertEquals(EnvironmentToolCatalog.version(), hello.path("toolCatalogVersion").asText());
    assertTrue(codec.readPayload(handshake.get(1)).path("skills").isArray());

    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 生产构造器不能为不完整的 registry 公布固定的 catalog。 */
  @Test
  void productionRuntimeRejectsRegistryThatDoesNotMatchFixedCatalog() {
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(new TestTool());
    DaemonConfig config =
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "environment",
            "daemon",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            "test-gateway-token",
            List.of());

    assertThrows(
        IllegalStateException.class,
        () -> new DaemonRuntime(config, ENVIRONMENT_ID, registry, DaemonSkillRegistry.empty()));
  }

  /**
   * Daemon 发出的 READY payload 必须能被 Cloud 共享的 codec 解码回完整 {@link DaemonSkillDescriptor} 列表，避免
   * Cloud/Daemon 协议漂移。
   */
  @Test
  void readySkillsPayloadIsFullyDecodableBySharedCodec() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills-codec");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"), "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n");
    try {
      DaemonSkillsCodec skillsCodec = new DaemonSkillsCodec();
      FakeTransport transport = new FakeTransport();
      runtime =
          runtime(transport, new TestTool(), DaemonSkillRegistry.discover(List.of(skillRoot)));

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      List<DaemonSkillDescriptor> skills = skillsCodec.decode(handshake.get(1).payloadJson());

      assertEquals(1, skills.size());
      assertEquals("demo", skills.get(0).name());
      assertEquals("Demo skill", skills.get(0).description());
      assertNotNull(skills.get(0).description());
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** READY 后必须在配置周期内发送 HEARTBEAT。 */
  @Test
  void sendsHeartbeatWhileReady() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool(), Duration.ofMillis(20));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    List<DaemonEnvelope> handshake = transport.takeMessages(3);
    assertMessageTypes(handshake, HELLO, READY, DaemonMessageType.HEARTBEAT);

    assertMessageTypes(List.of(transport.takeNextMessage()), DaemonMessageType.HEARTBEAT);
  }

  /** 首次连接失败后必须按重连生命周期再次尝试并完成握手。 */
  @Test
  void reconnectsAfterConnectionFailure() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.failNextConnection();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(2);
    completeHandshake(0);

    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
  }

  /** 任一出站 send failure 都使连接失效；重连后 journal 仍阻止 invocation 重启。 */
  @Test
  void reconnectsAfterSendFailureWithoutRestartingInvocation() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(8);
    transport.takeMessages(2);
    transport.failNextSend();
    transport.receive(invoke("send-failure", 9));
    transport.awaitConnections(1);
    completeHandshake(0);
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("send-failure", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** 未注册工具必须以 FAILED 终态返回，而不是让协议处理线程失败。 */
  @Test
  void returnsFailedTerminalForUnknownTool() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("unknown-invocation", 1, "missing"));

    List<DaemonEnvelope> messages = transport.takeMessages(2);
    assertMessageTypes(messages, ACK, DaemonMessageType.FAILED);
    assertTrue(messages.get(1).payloadJson().contains("unknown environment tool"));
  }

  /** 引用错误的 Environment（id 或 display name）或非法 INVOKE payload 必须得到明确 ERROR 响应。 */
  @Test
  void rejectsWrongScopeAndMalformedInvocationPayload() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "other-environment",
            "wrong-scope",
            1,
            "{\"toolName\":\"test\",\"arguments\":{}}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.INVOKE,
            new EnvironmentId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            "environment",
            "wrong-id",
            1,
            "{\"toolName\":\"test\",\"arguments\":{}}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "environment",
            "bad-payload",
            2,
            "{\"toolName\":\"test\",\"arguments\":[]}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
  }

  /** v1 协议消息必须在 codec 边界拒绝，不会触达 scope 或 invocation 生命周期。 */
  @Test
  void rejectsLegacyVersionOneEnvelopes() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":1,\"messageType\":\"INVOKE\","
            + "\"environmentName\":\"environment\",\"sequence\":1,"
            + "\"payload\":{\"toolName\":\"test\",\"toolVersion\":\"1.0.0\",\"arguments\":{}}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-v1", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
  }

  /** 缺失 invocationId 在 codec 边界失败，不得触达 journal 或 Tool SPI。 */
  @Test
  void rejectsInvokeWithoutInvocationIdBeforeSideEffects() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":2,\"messageType\":\"INVOKE\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\","
            + "\"environmentName\":\"environment\",\"sequence\":1,\"payload\":{\"toolName\":\"test\","
            + "\"toolVersion\":\"1.0.0\",\"arguments\":{}}}");

    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receiveRaw(
        "{\"protocolVersion\":2,\"messageType\":\"CANCEL\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\","
            + "\"environmentName\":\"environment\",\"sequence\":1,\"payload\":{}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-missing-id", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
  }

  /** 当前连接只接受连续 sequence；完全相同的最新 envelope 重发会 ACK 并复用 journal。 */
  @Test
  void rejectsOutOfOrderSequenceAndHandlesIdenticalReplayIdempotently()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(3);
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("sequence-invocation", 4);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("backward", 3));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(invoke("jump", 6));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(cancel("sequence-invocation", 5));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
  }

  /** 相同 sequence 的 messageType、invocationId 或 payload 冲突必须在任何副作用前拒绝。 */
  @Test
  void rejectsConflictingEnvelopeThatReusesLatestSequence() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(3);
    transport.takeMessages(2);
    transport.receive(invoke("original", 4));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

    transport.receive(invoke("different-invocation", 4));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(invoke("original", 4, "test", "1.0.0", 2000));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(cancel("original", 4));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(1, tool.executions.get());
    assertEquals(0, tool.handle.cancelCalls.get());

    transport.receive(invoke("different-invocation", 5));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(2, tool.executions.get());
  }

  /** ACK/ERROR 只推进连接 sequence，不创建 invocation 或发送额外响应；WELCOME 由握手阶段消耗。 */
  @Test
  void acceptsInboundPlatformProtocolMessagesWithinSequence() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(platformMessage(DaemonMessageType.ACK, 1));
    transport.receive(platformMessage(DaemonMessageType.ERROR, 2));
    assertFalse(transport.hasMessages());

    transport.receive(invoke("after-control", 3));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** Cloud 声明的工具版本必须匹配本地 descriptor，避免以错误参数契约启动 Tool。 */
  @Test
  void rejectsMismatchedToolVersion() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("version-mismatch", 1, "test", "2.0.0", 1000));

    assertMessageTypes(transport.takeMessages(2), ACK, DaemonMessageType.FAILED);
    assertEquals(0, tool.executions.get());
  }

  /** pi-base 的 filePath 别名必须在 strict descriptor 校验前归一化为 path。 */
  @Test
  void normalizesPiFilePathAliasBeforeStrictPathToolValidation() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool =
        new TestTool(
            "read",
            new ToolParamsSchema(
                "read arguments",
                Map.of("path", new ToolStringSchema("path")),
                Set.of("path"),
                false));
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "environment",
            "file-path-alias",
            1,
            "{\"toolName\":\"read\",\"toolVersion\":\"1.0.0\","
                + "\"arguments\":{\"filePath\":\"src/App.java\"},\"timeoutMillis\":1000}"));

    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals("{\"path\":\"src/App.java\"}", tool.request.call().argumentsJson());
  }

  /** 重复 INVOKE 仅重放 STARTED 或终态，不得再次调用本地 Tool。 */
  @Test
  void deduplicatesRunningInvocationAndReplaysTerminalAfterReconnect() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(6);
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("invocation-1", 7);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    tool.complete(
        new ToolResult("invocation-1", List.of(new TextToolContent("done")), false, "{}", false));
    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    assertTrue(terminal.get(0).payloadJson().contains("done"));
    tool.complete(new ToolResult("invocation-1", List.of(), false, "{}", false));
    assertFalse(transport.hasMessages());

    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("invocation-1", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, COMPLETED);
    assertEquals(1, tool.executions.get());
  }

  /** Runtime 在 deadline 主动 cancel Tool，并阻止 timeout 后的完成回调覆盖 FAILED。 */
  @Test
  void enforcesTimeoutAndGuardsLateCompletion() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("timeout", 1, "test", "1.0.0", 30));
    transport.takeMessages(2);

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    assertTrue(terminal.get(0).payloadJson().contains("timed out"));
    assertEquals(1, tool.handle.cancelCalls.get());
    tool.complete(new ToolResult("timeout", List.of(), false, "{}", false));
    assertFalse(transport.hasMessages());
  }

  /** 缺省 timeoutMillis 表示不覆盖，必须优先使用 Tool descriptor timeout。 */
  @Test
  void omittedTimeoutUsesDescriptorTimeout() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(30));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-descriptor-timeout", 1, "test", "1.0.0"));
    transport.takeMessages(2);

    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
  }

  /** timeoutMillis 缺省且 descriptor 为 0 时，必须回退 daemon 默认 timeout。 */
  @Test
  void omittedTimeoutFallsBackToDaemonDefault() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    DefaultTimeoutTool tool = new DefaultTimeoutTool();
    runtime = runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(12));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-default-timeout", 1, "fallback", "1.0.0"));
    transport.takeMessages(2);

    assertEquals(Duration.ofSeconds(12), tool.request.effectiveTimeout());
  }

  /** 0 timeout 使用 descriptor timeout，完成或取消时 deadline 必须被撤销。 */
  @Test
  void resolvesZeroTimeoutAndCancelsDeadlineAfterCompletionOrCancellation()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("zero-timeout", 1, "test", "1.0.0", 0));
    transport.takeMessages(2);
    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
    tool.complete(new ToolResult("zero-timeout", List.of(), false, "{}", false));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);

    transport.receive(invoke("cancel-before-timeout", 2, "test", "1.0.0", 30));
    transport.takeMessages(2);
    transport.receive(cancel("cancel-before-timeout", 3));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(80)));
  }

  /** descriptor 也为 0 时必须回退 daemon 默认 timeout，仍不能产生无限执行。 */
  @Test
  void fallsBackToDaemonTimeoutWhenRequestAndDescriptorAreZero() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    DefaultTimeoutTool tool = new DefaultTimeoutTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("default-timeout", 1, "fallback", "1.0.0", 0));
    transport.takeMessages(2);

    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
    tool.complete();
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
  }

  /** complete 抢先终态后 deadline 必须失效且不能 cancel handle。 */
  @Test
  void completionWinsAgainstPendingTimeout() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("complete-before-timeout", 1, "test", "1.0.0", 50));
    transport.takeMessages(2);
    tool.complete(new ToolResult("complete-before-timeout", List.of(), false, "{}", false));

    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(0, tool.handle.cancelCalls.get());
  }

  /** 流式 PARTIAL 只承载 text/json；resource 内容在 PARTIAL 路径被拒绝并收敛为 FAILED。 */
  @Test
  void partialSerializesTextAndJsonButRejectsResourceContents() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryResourceStore store = new InMemoryResourceStore();
    ResourceRef stored = store.store(new byte[] {1, 2}, "application/json");
    runtime = runtime(transport, tool, store);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("structured-content", 1));
    transport.takeMessages(2);
    tool.partial(
        new ToolResult(
            "structured-content",
            List.of(new JsonToolContent("[1,2]"), new TextToolContent("hi")),
            false,
            "{}",
            false));

    List<DaemonEnvelope> messages = transport.takeMessages(1);
    assertMessageTypes(messages, PARTIAL);
    String payload = messages.get(0).payloadJson();
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":[1,2]"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hi\""));

    // PARTIAL 携带 resource → 编码在任何 store 访问前拒绝，收敛为 FAILED。
    transport.receive(invoke("structured-content-2", 2));
    transport.takeMessages(2);
    tool.partial(
        new ToolResult(
            "structured-content-2", List.of(new ResourceToolContent(stored)), false, "{}", false));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));
  }

  /** wire resource 必须包含 Base64 字节，使接收端可独立持久化；终端 payload 自包含，不依赖连接内映射。 */
  @Test
  void resourcePayloadIsSelfContainedAndDecodesOnReceiver() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryResourceStore store = new InMemoryResourceStore();
    byte[] data = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
    ResourceRef stored = store.store(data, "application/octet-stream");
    runtime = runtime(transport, tool, store);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("resource-rewrite", 1));
    transport.takeMessages(2);
    tool.complete(
        new ToolResult(
            "resource-rewrite", List.of(new ResourceToolContent(stored)), false, "{}", false));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    String payload = terminal.get(0).payloadJson();
    JsonNode resultNode = codec.readPayload(terminal.get(0)).get("result");
    JsonNode content = resultNode.get("contents").get(0);
    assertEquals("resource", content.get("type").asText());
    assertEquals(stored.uri(), content.get("uri").asText());
    assertEquals(stored.mediaType(), content.get("mediaType").asText());
    assertEquals(stored.size(), content.get("size").asLong());
    assertEquals(stored.sha256(), content.get("sha256").asText());
    String base64 = content.get("contentBase64").asText();
    assertEquals(Base64.getEncoder().encodeToString(data), base64);

    // 接收端解码为内联二进制内容；持久化外部存储由 ToolGateway 负责。
    DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
    ToolResult decoded = resultCodec.decodeResult(payload);
    assertEquals(1, decoded.contents().size());
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertArrayEquals(data, binary.content());
  }

  /** BinaryToolContent 必须先经 resource store 落盘再编码为 wire resource，wire ref 可被 store 读回。 */
  @Test
  void storesBinaryToolContentBeforeEncoding() throws Exception {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryResourceStore store = new InMemoryResourceStore();
    runtime = runtime(transport, tool, store);
    byte[] data = new byte[] {1, 2, 3};

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("binary-content", 1));
    transport.takeMessages(2);
    tool.complete(
        new ToolResult(
            "binary-content",
            List.of(new BinaryToolContent("application/octet-stream", data)),
            false,
            "{}",
            false));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    JsonNode content = codec.readPayload(terminal.get(0)).get("result").get("contents").get(0);
    assertEquals("resource", content.get("type").asText());
    assertEquals("application/octet-stream", content.get("mediaType").asText());
    assertEquals(3, content.get("size").asLong());
    assertEquals(Base64.getEncoder().encodeToString(data), content.get("contentBase64").asText());
    ResourceRef wireRef =
        new ResourceRef(
            content.get("uri").asText(),
            content.get("mediaType").asText(),
            null,
            content.get("size").asLong(),
            content.get("sha256").asText());
    assertArrayEquals(data, store.read(wireRef));
  }

  /** resource reader / 编码失败必须让 PARTIAL/COMPLETED 收敛为 FAILED，callback 不会泄漏 local-only ref。 */
  @Test
  void convergesResourceFailuresToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    ResourceStore failingStore =
        new ResourceStore() {
          @Override
          public ResourceRef store(byte[] bytes, String mediaType) throws IOException {
            throw new IOException("store down");
          }

          @Override
          public byte[] read(ResourceRef ref) throws IOException {
            throw new IOException("missing resource: " + ref.uri());
          }
        };
    runtime = runtime(transport, tool, failingStore);
    ResourceRef local =
        new ResourceRef("file:///export/local-1", "application/json", null, 3L, "0".repeat(64));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("resource-fail", 1));
    transport.takeMessages(2);

    // PARTIAL 失败必须收敛为 FAILED，且不再发出 PARTIAL 或 COMPLETED。
    tool.partial(
        new ToolResult(
            "resource-fail", List.of(new ResourceToolContent(local)), false, "{}", false));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));

    // FAILED 之后迟到的 COMPLETED 必须被忽略（由 journal 守卫）。
    tool.complete(
        new ToolResult(
            "resource-fail", List.of(new ResourceToolContent(local)), false, "{}", false));
    assertFalse(transport.hasMessages());

    // 现在一次带 COMPLETED 失败的独立 invocation 也必须收敛为 FAILED。
    transport.receive(invoke("resource-fail-2", 2));
    transport.takeMessages(2);
    tool.complete(
        new ToolResult(
            "resource-fail-2",
            List.of(
                new ResourceToolContent(
                    new ResourceRef(
                        "file:///export/local-2", "text/plain", null, 1L, "0".repeat(64)))),
            false,
            "{}",
            false));
    List<DaemonEnvelope> completeFailure = transport.takeMessages(1);
    assertMessageTypes(completeFailure, DaemonMessageType.FAILED);
    assertTrue(completeFailure.get(0).payloadJson().contains("cannot complete"));
  }

  /** 无 resource store 的 generic runtime 遇 resource 必须确定性 FAILED，不能发送不可解析的内容。 */
  @Test
  void failsClosedWhenResourceStoreIsAbsent() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool); // no resource store

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("no-source", 1));
    transport.takeMessages(2);

    tool.complete(
        new ToolResult(
            "no-source",
            List.of(
                new ResourceToolContent(
                    new ResourceRef(
                        "file:///export/local-only",
                        "application/json",
                        null,
                        0L,
                        "0".repeat(64)))),
            false,
            "{}",
            false));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    String payload = terminal.get(0).payloadJson();
    assertTrue(payload.contains("cannot complete"));
    assertFalse(payload.contains("\"type\":\"resource\""));
    assertFalse(payload.contains("\"contentBase64\""));
  }

  /** Tool error 和错误关联 ID 的完成回调都必须收敛为 FAILED。 */
  @Test
  void convertsToolErrorsAndMismatchedResultsToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("tool-error", 1));
    transport.takeMessages(2);
    tool.error(new IllegalStateException("tool failed"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);

    transport.receive(invoke("wrong-result", 2));
    transport.takeMessages(2);
    tool.complete(new ToolResult("another-id", List.of(), false, "{}", false));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);
  }

  /** PARTIAL 必须流式转发，CANCEL 后迟到 complete callback 不能覆盖 CANCELLED 终态。 */
  @Test
  void forwardsPartialAndGuardsCancelledInvocationAgainstLateCallbacks()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("invocation-2", 1));
    transport.takeMessages(2);

    tool.partial(
        new ToolResult("invocation-2", List.of(new TextToolContent("chunk")), false, "{}", false));
    List<DaemonEnvelope> partial = transport.takeMessages(1);
    assertMessageTypes(partial, PARTIAL);
    assertTrue(partial.get(0).payloadJson().contains("chunk"));

    transport.receive(cancel("invocation-2", 2));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
    assertEquals(1, tool.handle.cancelCalls.get());

    tool.complete(
        new ToolResult("invocation-2", List.of(new TextToolContent("late")), false, "{}", false));
    assertFalse(transport.hasMessages());
  }

  /** READY 只携带 skills 摘要，且 skills 不含本地路径。 */
  @Test
  void announcesSkillsAlongsideToolsInCapabilities() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    String body = "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n";
    Files.writeString(skillDir.resolve("SKILL.md"), body);
    try {
      FakeTransport transport = new FakeTransport();
      DaemonSkillRegistry skills = DaemonSkillRegistry.discover(List.of(skillRoot));
      runtime = runtime(transport, new TestTool(), skills);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      JsonNode payload = codec.readPayload(handshake.get(1));
      assertFalse(payload.has("tools"));
      assertEquals(1, payload.path("skills").size());
      assertEquals("demo", payload.path("skills").get(0).path("name").asText());
      assertEquals("Demo skill", payload.path("skills").get(0).path("description").asText());
      assertTrue(payload.path("skills").get(0).path("path").isMissingNode());
      assertTrue(payload.path("skills").get(0).path("content").isMissingNode());
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** LOAD_SKILL 通过 invocationId 关联，成功返回完整 SKILL.md 正文。 */
  @Test
  void loadsSkillBodyByName() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills-load");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    String body = "---\nname: demo\ndescription: Demo skill\n---\n# Demo\nfull body\n";
    Files.writeString(skillDir.resolve("SKILL.md"), body);
    try {
      FakeTransport transport = new FakeTransport();
      runtime =
          runtime(transport, new TestTool(), DaemonSkillRegistry.discover(List.of(skillRoot)));
      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      transport.takeMessages(2);

      DaemonSkillLoadCodec skillCodec = new DaemonSkillLoadCodec();
      transport.receive(
          new DaemonEnvelope(
              DaemonProtocol.VERSION_2,
              DaemonMessageType.LOAD_SKILL,
              ENVIRONMENT_ID,
              "environment",
              "skill-1",
              1,
              skillCodec.encodeRequest(new DaemonSkillLoadCodec.LoadSkillRequest("demo"))));

      List<DaemonEnvelope> messages = transport.takeMessages(2);
      assertMessageTypes(messages, ACK, DaemonMessageType.SKILL_LOADED);
      assertEquals("skill-1", messages.get(1).invocationId());
      DaemonSkillLoadCodec.SkillLoaded loaded =
          skillCodec.decodeLoaded(messages.get(1).payloadJson());
      assertEquals("demo", loaded.name());
      assertEquals(body, loaded.content());
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** 未知 skill 返回确定性 SKILL_LOAD_FAILED，不进入 tool journal。 */
  @Test
  void failsUnknownSkillLoadDeterministically() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool(), DaemonSkillRegistry.empty());
    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    DaemonSkillLoadCodec skillCodec = new DaemonSkillLoadCodec();
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.LOAD_SKILL,
            ENVIRONMENT_ID,
            "environment",
            "skill-missing",
            1,
            skillCodec.encodeRequest(new DaemonSkillLoadCodec.LoadSkillRequest("nope"))));

    List<DaemonEnvelope> messages = transport.takeMessages(2);
    assertMessageTypes(messages, ACK, DaemonMessageType.SKILL_LOAD_FAILED);
    DaemonSkillLoadCodec.SkillLoadFailed failed =
        skillCodec.decodeFailed(messages.get(1).payloadJson());
    assertEquals("nope", failed.name());
    assertTrue(failed.message().contains("unknown skill"));
  }

  /** transport 同步抛错和异步返回空连接都必须收敛为下一次重连。 */
  @Test
  void reconnectsWhenTransportThrowsOrCompletesWithNullConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.throwNextConnection();
    transport.returnNullNextConnection();
    runtime = runtime(transport, new TestTool());

    runtime.start();

    transport.awaitConnections(3);
    completeHandshake(0);
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** close 先于异步 connect 完成时，迟到连接必须立即关闭而不能重新激活 runtime。 */
  @Test
  void closesConnectionThatCompletesAfterRuntimeShutdown() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.delayNextConnection();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    runtime.close();
    transport.completeDelayedConnection();

    assertTrue(transport.connection.awaitClosed(Duration.ofSeconds(ASYNC_TEST_TIMEOUT_SECONDS)));
    assertFalse(transport.connection.isOpen());
    assertFalse(transport.hasMessages());
  }

  /** 上一代连接迟到的消息不得进入当前连接的 sequence 或 invocation 生命周期。 */
  @Test
  void ignoresLateMessageFromSupersededConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(0, invoke("stale-invocation", 1));
    assertFalse(transport.hasMessages());
    assertEquals(0, tool.executions.get());

    transport.receive(invoke("current-invocation", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** CANCEL 的即时 ACK 只允许回到接收该消息的连接，不能泄漏到重连后的连接。 */
  @Test
  void doesNotRouteStaleCancelAcknowledgementToReplacementConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(0, cancel("stale-cancel", 1));
    assertFalse(transport.hasMessages());

    transport.receive(cancel("current-cancel", 1));
    assertMessageTypes(transport.takeMessages(1), ACK);
  }

  /** stale LOAD_SKILL 响应及其协议 ERROR 均不得被发送到 replacement connection。 */
  @Test
  void doesNotRouteStaleSkillOrMalformedInputResponsesToReplacementConnection()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    DaemonSkillLoadCodec skillCodec = new DaemonSkillLoadCodec();
    runtime = runtime(transport, new TestTool(), DaemonSkillRegistry.empty());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(
        0,
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.LOAD_SKILL,
            ENVIRONMENT_ID,
            "environment",
            "stale-skill",
            1,
            skillCodec.encodeRequest(new DaemonSkillLoadCodec.LoadSkillRequest("missing"))));
    transport.receiveRawFromConnection(
        0,
        "{\"protocolVersion\":2,\"messageType\":\"INVOKE\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\","
            + "\"environmentName\":\"environment\",\"sequence\":2,\"payload\":{}}");
    assertFalse(transport.hasMessages());

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.LOAD_SKILL,
            ENVIRONMENT_ID,
            "environment",
            "current-skill",
            1,
            skillCodec.encodeRequest(new DaemonSkillLoadCodec.LoadSkillRequest("missing"))));
    assertMessageTypes(transport.takeMessages(2), ACK, DaemonMessageType.SKILL_LOAD_FAILED);
  }

  /** 被提前关闭的 scheduler 不能让 runtime 停在 started=true 但永远不会连接的半启动状态。 */
  @Test
  void remainsStoppedWhenSchedulerRejectsStartup() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.shutdownNow();
    runtime = runtime(transport, new TestTool(), scheduler);

    runtime.start();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
    runtime.close();
    assertTrue(transport.closed.get());
  }

  /** heartbeat 已创建但 reconnect 被拒绝时，start 必须撤销 heartbeat 并恢复 STOPPED。 */
  @Test
  void remainsStoppedWhenSchedulerRejectsInitialReconnect() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    ScheduledExecutorService scheduler = new ReconnectRejectingScheduler();
    runtime = runtime(transport, new TestTool(), scheduler);

    runtime.start();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
    runtime.close();
    assertTrue(transport.closed.get());
  }

  /** 未预期 platform 消息、未知取消和非法 skill payload 都必须只产生协议级响应。 */
  @Test
  void isolatesUnexpectedAndMalformedProtocolMessagesFromInvocationExecution()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.HELLO,
            ENVIRONMENT_ID,
            "environment",
            null,
            1,
            "{}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(cancel("unknown-cancel", 1));
    assertMessageTypes(transport.takeMessages(1), ACK);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            DaemonMessageType.LOAD_SKILL,
            ENVIRONMENT_ID,
            "environment",
            "invalid-skill-payload",
            2,
            "{}"));
    assertMessageTypes(transport.takeMessages(2), ACK, DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
  }

  /** close 必须取消运行中的 Tool、记录可重放 CANCELLED，并成为不可重启的终态。 */
  @Test
  void closeCancelsRunningInvocationAndPreventsRestart() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("shutdown-invocation", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

    runtime.close();

    assertTrue(transport.closed.get());
    assertEquals(1, tool.handle.cancelCalls.get());
    assertEquals(
        DaemonInvocationState.CANCELLED, journal.find("shutdown-invocation").orElseThrow().state());
    tool.complete(
        new ToolResult(
            "shutdown-invocation", List.of(new TextToolContent("late")), false, "{}", false));
    assertFalse(transport.hasMessages());

    runtime.start();
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
  }

  /** 未启动的 runtime 仍必须释放其 transport 和 scheduler，且 close 后不能重新启动。 */
  @Test
  void closesUnstartedRuntimeWithoutAllowingLaterStart() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.close();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertTrue(transport.closed.get());
    runtime.start();
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
  }

  /** shutdown 与 Tool 启动交错时，迟到的 execution handle 也必须收到取消且 journal 不得遗留 RUNNING。 */
  @Test
  void closesInvocationThatCompletesToolStartupAfterShutdown() throws Exception {
    FakeTransport transport = new FakeTransport();
    BlockingTool tool = new BlockingTool();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    Thread invocationThread =
        new Thread(
            () -> transport.receive(invoke("shutdown-race", 1, "blocking")),
            "invoke-shutdown-race");
    invocationThread.start();
    assertTrue(tool.executionStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

    runtime.close();
    tool.allowReturn.countDown();
    invocationThread.join(TimeUnit.SECONDS.toMillis(ASYNC_TEST_TIMEOUT_SECONDS));

    assertFalse(invocationThread.isAlive());
    assertEquals(1, tool.handle.cancelCalls.get());
    assertEquals(
        DaemonInvocationState.CANCELLED, journal.find("shutdown-race").orElseThrow().state());
    assertFalse(transport.hasMessages());
  }

  private DaemonRuntime runtime(FakeTransport transport, Tool tool) {
    return runtime(transport, tool, Duration.ofMinutes(1));
  }

  private DaemonRuntime runtime(FakeTransport transport, Tool tool, Duration heartbeatInterval) {
    return runtime(transport, tool, heartbeatInterval, Duration.ofSeconds(10));
  }

  private DaemonRuntime runtime(FakeTransport transport, Tool tool, ResourceStore resourceStore) {
    return runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(10), resourceStore);
  }

  private DaemonRuntime runtime(
      FakeTransport transport, Tool tool, InMemoryDaemonInvocationJournal journal) {
    handshakeTransport = transport;
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(tool);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "environment",
            "daemon",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            "test-gateway-token",
            List.of()),
        ENVIRONMENT_ID,
        transport,
        registry,
        DaemonSkillRegistry.empty(),
        journal,
        Executors.newSingleThreadScheduledExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport, Tool tool, ScheduledExecutorService scheduler) {
    handshakeTransport = transport;
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(tool);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "environment",
            "daemon",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            "test-gateway-token",
            List.of()),
        ENVIRONMENT_ID,
        transport,
        registry,
        DaemonSkillRegistry.empty(),
        new InMemoryDaemonInvocationJournal(),
        scheduler);
  }

  private DaemonRuntime runtime(
      FakeTransport transport, Tool tool, Duration heartbeatInterval, Duration defaultToolTimeout) {
    return runtime(transport, tool, heartbeatInterval, defaultToolTimeout, null);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      Tool tool,
      Duration heartbeatInterval,
      Duration defaultToolTimeout,
      ResourceStore resourceStore) {
    return runtime(
        transport,
        tool,
        DaemonSkillRegistry.empty(),
        heartbeatInterval,
        defaultToolTimeout,
        resourceStore);
  }

  private DaemonRuntime runtime(
      FakeTransport transport, Tool tool, DaemonSkillRegistry skillRegistry) {
    return runtime(
        transport, tool, skillRegistry, Duration.ofMinutes(1), Duration.ofSeconds(10), null);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      Tool tool,
      DaemonSkillRegistry skillRegistry,
      Duration heartbeatInterval,
      Duration defaultToolTimeout,
      ResourceStore resourceStore) {
    handshakeTransport = transport;
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(tool);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "environment",
            "daemon",
            heartbeatInterval,
            Duration.ZERO,
            Duration.ofSeconds(1),
            defaultToolTimeout,
            "test-gateway-token",
            List.of()),
        ENVIRONMENT_ID,
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        resourceStore);
  }

  private void deleteRecursively(Path root) throws Exception {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ignored) {
                  // 尽力清理 temp skill fixture
                }
              });
    }
  }

  /**
   * 模拟 Cloud 完成 HELLO/WELCOME/READY 握手：发送 WELCOME 让 daemon 推进到 READY。后续入站 sequence 必须从 {@code
   * welcomeSequence + 1} 起严格递增。
   */
  private void completeHandshake(long welcomeSequence) throws InterruptedException {
    handshakeTransport.awaitNextMessageType(DaemonMessageType.HELLO);
    handshakeTransport.receive(platformMessage(DaemonMessageType.WELCOME, welcomeSequence));
  }

  private DaemonEnvelope invoke(String invocationId, long sequence) {
    return invoke(invocationId, sequence, "test");
  }

  private DaemonEnvelope invoke(String invocationId, long sequence, String toolName) {
    return invoke(invocationId, sequence, toolName, "1.0.0", 1000);
  }

  private DaemonEnvelope invoke(
      String invocationId, long sequence, String toolName, String toolVersion, long timeoutMillis) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        "environment",
        invocationId,
        sequence,
        "{\"toolName\":\""
            + toolName
            + "\",\"toolVersion\":\""
            + toolVersion
            + "\",\"arguments\":{},\"timeoutMillis\":"
            + timeoutMillis
            + "}");
  }

  private DaemonEnvelope invokeWithoutTimeout(
      String invocationId, long sequence, String toolName, String toolVersion) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        "environment",
        invocationId,
        sequence,
        "{\"toolName\":\""
            + toolName
            + "\",\"toolVersion\":\""
            + toolVersion
            + "\",\"arguments\":{}}");
  }

  private DaemonEnvelope platformMessage(DaemonMessageType messageType, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2, messageType, ENVIRONMENT_ID, "environment", null, sequence, "{}");
  }

  private DaemonEnvelope cancel(String invocationId, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.CANCEL,
        ENVIRONMENT_ID,
        "environment",
        invocationId,
        sequence,
        "{}");
  }

  private List<String> jsonTexts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private void assertMessageTypes(List<DaemonEnvelope> envelopes, DaemonMessageType... expected) {
    assertEquals(List.of(expected), envelopes.stream().map(DaemonEnvelope::messageType).toList());
  }

  private final class FakeTransport implements DaemonTransport {

    private final Semaphore connections = new Semaphore(0);
    private final LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final AtomicBoolean failNextConnection = new AtomicBoolean();
    private final AtomicBoolean failNextSend = new AtomicBoolean();
    private final AtomicBoolean throwNextConnection = new AtomicBoolean();
    private final AtomicBoolean returnNullNextConnection = new AtomicBoolean();
    private final AtomicBoolean delayNextConnection = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<DaemonTransportListener> listeners = new CopyOnWriteArrayList<>();
    private volatile DaemonTransportListener listener;
    private volatile FakeConnection connection;
    private volatile CompletableFuture<DaemonConnection> delayedConnection;

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      this.listener = listener;
      listeners.add(listener);
      connections.release();
      if (throwNextConnection.compareAndSet(true, false)) {
        throw new IllegalStateException("connection threw");
      }
      if (failNextConnection.compareAndSet(true, false)) {
        return CompletableFuture.failedFuture(new IllegalStateException("connection failed"));
      }
      if (returnNullNextConnection.compareAndSet(true, false)) {
        return CompletableFuture.completedFuture(null);
      }
      connection = new FakeConnection();
      if (delayNextConnection.compareAndSet(true, false)) {
        delayedConnection = new CompletableFuture<>();
        return delayedConnection;
      }
      return CompletableFuture.completedFuture(connection);
    }

    private void failNextConnection() {
      failNextConnection.set(true);
    }

    private void failNextSend() {
      failNextSend.set(true);
    }

    private void throwNextConnection() {
      throwNextConnection.set(true);
    }

    private void returnNullNextConnection() {
      returnNullNextConnection.set(true);
    }

    private void delayNextConnection() {
      delayNextConnection.set(true);
    }

    private void completeDelayedConnection() {
      delayedConnection.complete(connection);
    }

    private void receive(DaemonEnvelope envelope) {
      receiveRaw(codec.encode(envelope));
    }

    private void receiveRaw(String message) {
      listener.onMessage(message);
    }

    private void receiveFromConnection(int connectionIndex, DaemonEnvelope envelope) {
      listeners.get(connectionIndex).onMessage(codec.encode(envelope));
    }

    private void receiveRawFromConnection(int connectionIndex, String message) {
      listeners.get(connectionIndex).onMessage(message);
    }

    private void disconnect() {
      connection.open = false;
      listener.onDisconnected(null);
    }

    private void awaitConnections(int expected) throws InterruptedException {
      assertTrue(connections.tryAcquire(expected, ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private boolean awaitConnection(Duration timeout) throws InterruptedException {
      return connections.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private List<DaemonEnvelope> takeMessages(int count) throws InterruptedException {
      List<DaemonEnvelope> messages = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String message = sent.poll(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(message != null, "expected daemon message " + (index + 1));
        messages.add(codec.decode(message));
      }
      return messages;
    }

    private void awaitNextMessageType(DaemonMessageType expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
      String message;
      while ((message = sent.peek()) == null && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(1);
      }
      assertTrue(message != null, "expected daemon message " + expected);
      assertEquals(expected, codec.decode(message).messageType());
    }

    private DaemonEnvelope takeNextMessage() throws InterruptedException {
      String message = sent.poll(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(message != null, "expected daemon message");
      return codec.decode(message);
    }

    private boolean hasMessages() {
      return !sent.isEmpty();
    }

    private boolean awaitMessage(Duration timeout) throws InterruptedException {
      return sent.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) != null;
    }

    @Override
    public void close() {
      closed.set(true);
    }

    private final class FakeConnection implements DaemonConnection {

      private volatile boolean open = true;
      private final CountDownLatch closed = new CountDownLatch(1);

      @Override
      public CompletionStage<Void> sendText(String message) {
        if (failNextSend.compareAndSet(true, false)) {
          open = false;
          return CompletableFuture.failedFuture(new IllegalStateException("send failed"));
        }
        sent.add(message);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void close() {
        open = false;
        closed.countDown();
      }

      @Override
      public boolean isOpen() {
        return open;
      }

      private boolean awaitClosed(Duration timeout) throws InterruptedException {
        return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  private static final class DefaultTimeoutTool implements Tool {

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "fallback",
            "1.0.0",
            ToolType.ENVIRONMENT,
            "default timeout tool",
            null,
            new ToolParamsSchema("fallback arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    private final TestHandle handle = new TestHandle();
    private volatile ToolExecutionListener listener;
    private volatile ToolExecutionRequest request;

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      this.request = request;
      this.listener = listener;
      return handle;
    }

    private void complete() {
      listener.onComplete(new ToolResult(request.call().id(), List.of(), false, "{}", false));
    }
  }

  private static final class TestTool implements Tool {

    private final ToolDescriptor descriptor;
    private final AtomicInteger executions = new AtomicInteger();
    private final TestHandle handle = new TestHandle();
    private volatile ToolExecutionListener listener;
    private volatile ToolExecutionRequest request;

    private TestTool() {
      this("test", new ToolParamsSchema("test arguments", Map.of(), Set.of(), false));
    }

    private TestTool(String name, ToolParamsSchema schema) {
      descriptor =
          new ToolDescriptor(
              name,
              "1.0.0",
              ToolType.ENVIRONMENT,
              name + " tool",
              null,
              schema,
              ToolSideEffect.READ_ONLY,
              Duration.ofSeconds(10));
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      executions.incrementAndGet();
      this.request = request;
      this.listener = listener;
      return handle;
    }

    private void partial(ToolResult result) {
      listener.onPartial(result);
    }

    private void complete(ToolResult result) {
      listener.onComplete(result);
    }

    private void error(Throwable error) {
      listener.onError(error);
    }
  }

  private static final class BlockingTool implements Tool {

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "blocking",
            "1.0.0",
            ToolType.ENVIRONMENT,
            "blocking tool",
            null,
            new ToolParamsSchema("blocking arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));
    private final CountDownLatch executionStarted = new CountDownLatch(1);
    private final CountDownLatch allowReturn = new CountDownLatch(1);
    private final TestHandle handle = new TestHandle();

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      executionStarted.countDown();
      try {
        if (!allowReturn.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release blocking tool");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("blocking tool interrupted", error);
      }
      return handle;
    }
  }

  private static final class ReconnectRejectingScheduler extends ScheduledThreadPoolExecutor {

    private ReconnectRejectingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("reconnect scheduling rejected");
    }
  }

  private static final class TestHandle implements ToolExecutionHandle {

    private final AtomicInteger cancelCalls = new AtomicInteger();

    @Override
    public void cancel() {
      cancelCalls.incrementAndGet();
    }

    @Override
    public boolean isCancelled() {
      return cancelCalls.get() > 0;
    }
  }
}
