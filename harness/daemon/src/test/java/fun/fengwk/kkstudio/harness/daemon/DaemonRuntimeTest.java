package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.ACK;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.CANCELLED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.CAPABILITIES;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.COMPLETED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.PARTIAL;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.coding.ArtifactSource;
import fun.fengwk.kkstudio.harness.daemon.coding.InMemoryArtifactSink;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
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
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Daemon 生命周期及本地 Tool SPI 的协议集成测试。 */
class DaemonRuntimeTest {

  private static final long ASYNC_TEST_TIMEOUT_SECONDS = 5;

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
  private DaemonRuntime runtime;

  @AfterEach
  void closeRuntime() {
    if (runtime != null) {
      runtime.close();
    }
  }

  /** 断线后必须重连并重新完成 HELLO/CAPABILITIES/READY 的 READY/PULL 握手。 */
  @Test
  void reconnectsAfterDisconnectAndReannouncesReadiness() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    List<DaemonEnvelope> handshake = transport.takeMessages(3);
    assertMessageTypes(handshake, HELLO, CAPABILITIES, READY);
    assertEquals(
        "test-gateway-token", codec.readPayload(handshake.get(0)).path("gatewayToken").asText());
    JsonNode descriptor = codec.readPayload(handshake.get(1)).path("tools").get(0);
    assertEquals("test", descriptor.path("name").asText());
    assertEquals("1.0.0", descriptor.path("version").asText());
    assertEquals("test", descriptor.path("rendererKey").asText());
    assertFalse(descriptor.has("executionLocation"));
    assertEquals("READ_ONLY", descriptor.path("sideEffect").asText());
    assertEquals(10_000, descriptor.path("timeoutMillis").asLong());
    assertEquals("object", descriptor.path("inputSchema").path("type").asText());
    assertTrue(descriptor.path("inputSchema").path("properties").isObject());
    assertTrue(descriptor.path("inputSchema").path("required").isArray());

    transport.disconnect();
    transport.awaitConnections(1);
    assertMessageTypes(transport.takeMessages(3), HELLO, CAPABILITIES, READY);
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** CAPABILITIES 必须完整保留 object/array/enum/primitive schema，供 Cloud 做参数匹配。 */
  @Test
  void serializesCompleteToolSchemaInCapabilities() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new SchemaTool());

    runtime.start();
    transport.awaitConnections(1);
    List<DaemonEnvelope> handshake = transport.takeMessages(3);

    JsonNode schema = codec.readPayload(handshake.get(1)).path("tools").get(0).path("inputSchema");
    assertEquals("string", schema.path("properties").path("text").path("type").asText());
    assertEquals("integer", schema.path("properties").path("count").path("type").asText());
    assertEquals("number", schema.path("properties").path("ratio").path("type").asText());
    assertEquals("boolean", schema.path("properties").path("enabled").path("type").asText());
    assertEquals(
        List.of("fast", "safe"), jsonTexts(schema.path("properties").path("mode").path("enum")));
    assertEquals(
        "string", schema.path("properties").path("tags").path("items").path("type").asText());
    JsonNode nested = schema.path("properties").path("options");
    assertEquals("object", nested.path("type").asText());
    assertEquals("boolean", nested.path("properties").path("force").path("type").asText());
    assertEquals(List.of("force"), jsonTexts(nested.path("required")));
    assertTrue(nested.path("additionalProperties").asBoolean());
  }

  /**
   * Daemon 发出的 CAPABILITIES payload 必须能被 Cloud 共享的 codec 解码回完整 {@link
   * fun.fengwk.kkstudio.harness.tool.ToolDescriptor} 列表，避免 Cloud/Daemon 协议漂移。
   */
  @Test
  void capabilitiesPayloadIsFullyDecodableBySharedCodec() throws InterruptedException {
    DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new SchemaTool());

    runtime.start();
    transport.awaitConnections(1);
    List<DaemonEnvelope> handshake = transport.takeMessages(3);

    DaemonToolCapabilitiesCodec.DaemonToolCapabilities capabilities =
        codec.decode(handshake.get(1).payloadJson());

    assertEquals(1, capabilities.tools().size());
    assertEquals("schema", capabilities.tools().get(0).name());
    assertEquals("2.1.0", capabilities.tools().get(0).version());
    assertEquals("schema", capabilities.tools().get(0).name());
    assertNotNull(capabilities.tools().get(0).sideEffect());
  }

  /** READY 后必须在配置周期内发送 HEARTBEAT。 */
  @Test
  void sendsHeartbeatWhileReady() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool(), Duration.ofMillis(20));

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);

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

    assertMessageTypes(transport.takeMessages(3), HELLO, CAPABILITIES, READY);
  }

  /** 任一出站 send failure 都使连接失效；重连后 journal 仍阻止 invocation 重启。 */
  @Test
  void reconnectsAfterSendFailureWithoutRestartingInvocation() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.failNextSend();
    transport.receive(invoke("send-failure", 9));
    transport.awaitConnections(1);
    assertMessageTypes(transport.takeMessages(3), HELLO, CAPABILITIES, READY);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("send-failure", 0));
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
    transport.takeMessages(3);
    transport.receive(invoke("unknown-invocation", 1, "missing"));

    List<DaemonEnvelope> messages = transport.takeMessages(2);
    assertMessageTypes(messages, ACK, DaemonMessageType.FAILED);
    assertTrue(messages.get(1).payloadJson().contains("unknown environment tool"));
  }

  /** 引用错误的 Environment 或非法 INVOKE payload 必须得到明确 ERROR/FAILED 响应。 */
  @Test
  void rejectsWrongScopeAndMalformedInvocationPayload() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestTool());

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            DaemonMessageType.INVOKE,
            "other-environment",
            "wrong-scope",
            1,
            "{\"toolName\":\"test\",\"arguments\":{}}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            DaemonMessageType.INVOKE,
            "environment",
            "bad-payload",
            2,
            "{\"toolName\":\"test\",\"arguments\":[]}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
  }

  /** 缺失 invocationId 在 codec 边界失败，不得触达 journal 或 Tool SPI。 */
  @Test
  void rejectsInvokeWithoutInvocationIdBeforeSideEffects() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receiveRaw(
        "{\"protocolVersion\":1,\"messageType\":\"INVOKE\","
            + "\"environmentName\":\"environment\",\"sequence\":1,\"payload\":{\"toolName\":\"test\","
            + "\"toolVersion\":\"1.0.0\",\"arguments\":{}}}");

    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receiveRaw(
        "{\"protocolVersion\":1,\"messageType\":\"CANCEL\","
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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

  /** WELCOME/ACK/ERROR 只推进连接 sequence，不创建 invocation 或发送额外响应。 */
  @Test
  void acceptsInboundPlatformProtocolMessagesWithinSequence() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(platformMessage(DaemonMessageType.WELCOME, 20));
    transport.receive(platformMessage(DaemonMessageType.ACK, 21));
    transport.receive(platformMessage(DaemonMessageType.ERROR, 22));
    assertFalse(transport.hasMessages());

    transport.receive(invoke("after-control", 23));
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
    transport.takeMessages(3);
    transport.receive(invoke("version-mismatch", 1, "test", "2.0.0", 1000));

    assertMessageTypes(transport.takeMessages(2), ACK, DaemonMessageType.FAILED);
    assertEquals(0, tool.executions.get());
  }

  /** 重复 INVOKE 仅重放 STARTED 或终态，不得再次调用本地 Tool。 */
  @Test
  void deduplicatesRunningInvocationAndReplaysTerminalAfterReconnect() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
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
    transport.takeMessages(3);
    transport.receive(invoke("invocation-1", 0));
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
    transport.receive(invoke("complete-before-timeout", 1, "test", "1.0.0", 50));
    transport.takeMessages(2);
    tool.complete(new ToolResult("complete-before-timeout", List.of(), false, "{}", false));

    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(0, tool.handle.cancelCalls.get());
  }

  /** 流式结果必须保留 JSON 与 Artifact 等非文本内容的结构，并随 payload 自包含 artifact 字节。 */
  @Test
  void serializesJsonAndArtifactToolContents() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryArtifactSink sink = new InMemoryArtifactSink();
    ArtifactRef stored = sink.store(new byte[] {1, 2}, "application/json");
    runtime = runtime(transport, tool, sink);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke("structured-content", 1));
    transport.takeMessages(2);
    tool.partial(
        new ToolResult(
            "structured-content",
            List.of(new JsonToolContent("[1,2]"), new ArtifactToolContent(stored)),
            false,
            "{}",
            false));

    List<DaemonEnvelope> messages = transport.takeMessages(1);
    assertMessageTypes(messages, PARTIAL);
    String payload = messages.get(0).payloadJson();
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":[1,2]"));
    assertTrue(payload.contains("\"type\":\"artifact\""));
    assertTrue(payload.contains("\"artifactId\":\"" + stored.artifactId() + "\""));
    assertTrue(payload.contains("\"mediaType\":\"application/json\""));
    assertTrue(payload.contains("\"sizeBytes\":2"));
    assertTrue(payload.contains("\"contentBase64\":\"AQI=\""));
  }

  /** wire artifact 必须包含 Base64 字节，使接收端可独立持久化并替换为 global ref；终端 payload 自包含，不依赖连接内映射。 */
  @Test
  void artifactPayloadIsSelfContainedAndRefIsRewrittenOnReceiver() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    InMemoryArtifactSink sink = new InMemoryArtifactSink();
    byte[] data = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
    ArtifactRef stored = sink.store(data, "application/octet-stream");
    runtime = runtime(transport, tool, sink);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke("artifact-rewrite", 1));
    transport.takeMessages(2);
    tool.complete(
        new ToolResult(
            "artifact-rewrite", List.of(new ArtifactToolContent(stored)), false, "{}", false));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    String payload = terminal.get(0).payloadJson();
    JsonNode resultNode = codec.readPayload(terminal.get(0)).get("result");
    JsonNode content = resultNode.get("contents").get(0);
    assertEquals("artifact", content.get("type").asText());
    assertEquals(stored.artifactId(), content.get("artifactId").asText());
    assertEquals(stored.mediaType(), content.get("mediaType").asText());
    assertEquals(stored.sizeBytes(), content.get("sizeBytes").asLong());
    String base64 = content.get("contentBase64").asText();
    assertEquals(Base64.getEncoder().encodeToString(data), base64);

    // Receivers decode to inline binary content; durable externalization is ToolWorker ownership.
    DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
    ToolResult decoded = resultCodec.decodeResult(payload);
    assertEquals(1, decoded.contents().size());
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertArrayEquals(data, binary.content());
    assertTrue(
        payload.contains("\"contentBase64\":\"yv66vg==\"")
            || payload.contains("\"contentBase64\":\"" + base64 + "\""));
  }

  /** artifact reader / 编码失败必须让 PARTIAL/COMPLETED 收敛为 FAILED，callback 不会泄漏 local-only ref。 */
  @Test
  void convergesArtifactFailuresToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    ArtifactSource failingSource =
        ref -> {
          throw new IOException("missing artifact: " + ref.artifactId());
        };
    runtime = runtime(transport, tool, failingSource);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke("artifact-fail", 1));
    transport.takeMessages(2);

    // PARTIAL failure must converge to FAILED and not emit any PARTIAL or COMPLETED.
    tool.partial(
        new ToolResult(
            "artifact-fail",
            List.of(new ArtifactToolContent(new ArtifactRef("local-1", "application/json", 3))),
            false,
            "{}",
            false));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));

    // A late COMPLETED after FAILED must be ignored (journal guards).
    tool.complete(
        new ToolResult(
            "artifact-fail",
            List.of(new ArtifactToolContent(new ArtifactRef("local-1", "application/json", 3))),
            false,
            "{}",
            false));
    assertFalse(transport.hasMessages());

    // Now a separate invocation with COMPLETED failure must also converge to FAILED.
    transport.receive(invoke("artifact-fail-2", 2));
    transport.takeMessages(2);
    tool.complete(
        new ToolResult(
            "artifact-fail-2",
            List.of(new ArtifactToolContent(new ArtifactRef("local-2", "text/plain", 1))),
            false,
            "{}",
            false));
    List<DaemonEnvelope> completeFailure = transport.takeMessages(1);
    assertMessageTypes(completeFailure, DaemonMessageType.FAILED);
    assertTrue(completeFailure.get(0).payloadJson().contains("cannot complete"));
  }

  /** 无 artifact source 的 generic runtime 遇 artifact 必须确定性 FAILED，不能发送 local-only ref。 */
  @Test
  void failsClosedWhenArtifactSourceIsAbsent() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool); // no artifact source

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke("no-source", 1));
    transport.takeMessages(2);

    tool.complete(
        new ToolResult(
            "no-source",
            List.of(new ArtifactToolContent(new ArtifactRef("local-only", "application/json", 0))),
            false,
            "{}",
            false));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    String payload = terminal.get(0).payloadJson();
    assertTrue(payload.contains("cannot complete"));
    assertFalse(payload.contains("\"artifactId\":\"local-only\""));
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
    transport.takeMessages(3);
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
    transport.takeMessages(3);
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

  /** CAPABILITIES 必须同时携带 tools 与 skills 摘要，且 skills 不含本地路径。 */
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
      List<DaemonEnvelope> handshake = transport.takeMessages(3);
      assertMessageTypes(handshake, HELLO, CAPABILITIES, READY);

      JsonNode payload = codec.readPayload(handshake.get(1));
      assertEquals("test", payload.path("tools").get(0).path("name").asText());
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
      transport.takeMessages(3);

      DaemonSkillLoadCodec skillCodec = new DaemonSkillLoadCodec();
      transport.receive(
          new DaemonEnvelope(
              DaemonProtocol.VERSION_1,
              DaemonMessageType.LOAD_SKILL,
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
    transport.takeMessages(3);

    DaemonSkillLoadCodec skillCodec = new DaemonSkillLoadCodec();
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            DaemonMessageType.LOAD_SKILL,
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

  private DaemonRuntime runtime(FakeTransport transport, Tool tool) {
    return runtime(transport, tool, Duration.ofMinutes(1));
  }

  private DaemonRuntime runtime(FakeTransport transport, Tool tool, Duration heartbeatInterval) {
    return runtime(transport, tool, heartbeatInterval, Duration.ofSeconds(10));
  }

  private DaemonRuntime runtime(FakeTransport transport, Tool tool, ArtifactSource artifactSource) {
    return runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(10), artifactSource);
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
      ArtifactSource artifactSource) {
    return runtime(
        transport,
        tool,
        DaemonSkillRegistry.empty(),
        heartbeatInterval,
        defaultToolTimeout,
        artifactSource);
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
      ArtifactSource artifactSource) {
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
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        artifactSource);
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
                  // best-effort cleanup for temp skill fixtures
                }
              });
    }
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
        DaemonProtocol.VERSION_1,
        DaemonMessageType.INVOKE,
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
        DaemonProtocol.VERSION_1,
        DaemonMessageType.INVOKE,
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
        DaemonProtocol.VERSION_1, messageType, "environment", null, sequence, "{}");
  }

  private DaemonEnvelope cancel(String invocationId, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_1,
        DaemonMessageType.CANCEL,
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
    private volatile DaemonTransportListener listener;
    private volatile FakeConnection connection;

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      this.listener = listener;
      connections.release();
      if (failNextConnection.compareAndSet(true, false)) {
        return CompletableFuture.failedFuture(new IllegalStateException("connection failed"));
      }
      connection = new FakeConnection();
      return CompletableFuture.completedFuture(connection);
    }

    private void failNextConnection() {
      failNextConnection.set(true);
    }

    private void failNextSend() {
      failNextSend.set(true);
    }

    private void receive(DaemonEnvelope envelope) {
      receiveRaw(codec.encode(envelope));
    }

    private void receiveRaw(String message) {
      listener.onMessage(message);
    }

    private void disconnect() {
      connection.open = false;
      listener.onDisconnected(null);
    }

    private void awaitConnections(int expected) throws InterruptedException {
      assertTrue(connections.tryAcquire(expected, ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    private final class FakeConnection implements DaemonConnection {

      private volatile boolean open = true;

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
      }

      @Override
      public boolean isOpen() {
        return open;
      }
    }
  }

  private static final class SchemaTool implements Tool {

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "schema",
            "2.1.0",
            "schema tool",
            "schema-renderer",
            new ToolParamsSchema(
                "schema arguments",
                Map.of(
                    "text",
                    new ToolStringSchema("text value"),
                    "count",
                    new ToolIntegerSchema("count value"),
                    "ratio",
                    new ToolNumberSchema("ratio value"),
                    "enabled",
                    new ToolBooleanSchema("enabled value"),
                    "mode",
                    new ToolEnumSchema("execution mode", List.of("fast", "safe")),
                    "tags",
                    new ToolArraySchema("tag values", new ToolStringSchema("tag")),
                    "options",
                    new ToolObjectSchema(
                        "nested options",
                        Map.of("force", new ToolBooleanSchema("force execution")),
                        Set.of("force"),
                        true)),
                Set.of("text"),
                false),
            ToolSideEffect.IDEMPOTENT,
            Duration.ofSeconds(3));

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      throw new AssertionError("schema tool must not execute");
    }
  }

  private static final class DefaultTimeoutTool implements Tool {

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "fallback",
            "1.0.0",
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

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "test",
            "1.0.0",
            "test tool",
            null,
            new ToolParamsSchema("test arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));
    private final AtomicInteger executions = new AtomicInteger();
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
