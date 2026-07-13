package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.ACK;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.CANCELLED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.CAPABILITIES;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.COMPLETED;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.PARTIAL;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Daemon 生命周期及本地 Tool SPI 的协议集成测试。 */
class DaemonRuntimeTest {

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
    assertMessageTypes(transport.takeMessages(3), HELLO, CAPABILITIES, READY);

    transport.disconnect();
    transport.awaitConnections(1);
    assertMessageTypes(transport.takeMessages(3), HELLO, CAPABILITIES, READY);
    assertEquals(DaemonRuntimeState.READY, runtime.state());
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
    assertTrue(messages.get(1).payloadJson().contains("unknown local tool"));
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
    DaemonEnvelope invoke = invoke("invocation-1", 1);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    tool.complete(new ToolResult("invocation-1", List.of(new TextToolContent("done")), false, "{}", false));
    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    assertTrue(terminal.get(0).payloadJson().contains("done"));
    tool.complete(new ToolResult("invocation-1", List.of(), false, "{}", false));
    assertFalse(transport.hasMessages());

    transport.disconnect();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, COMPLETED);
    assertEquals(1, tool.executions.get());
  }

  /** PARTIAL 必须流式转发，CANCEL 后迟到 complete callback 不能覆盖 CANCELLED 终态。 */
  @Test
  void forwardsPartialAndGuardsCancelledInvocationAgainstLateCallbacks() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestTool tool = new TestTool();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    transport.takeMessages(3);
    transport.receive(invoke("invocation-2", 1));
    transport.takeMessages(2);

    tool.partial(new ToolResult("invocation-2", List.of(new TextToolContent("chunk")), false, "{}", false));
    List<DaemonEnvelope> partial = transport.takeMessages(1);
    assertMessageTypes(partial, PARTIAL);
    assertTrue(partial.get(0).payloadJson().contains("chunk"));

    transport.receive(cancel("invocation-2", 2));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
    assertEquals(1, tool.handle.cancelCalls.get());

    tool.complete(new ToolResult("invocation-2", List.of(new TextToolContent("late")), false, "{}", false));
    assertFalse(transport.hasMessages());
  }

  private DaemonRuntime runtime(FakeTransport transport, TestTool tool) {
    return runtime(transport, tool, Duration.ofMinutes(1));
  }

  private DaemonRuntime runtime(FakeTransport transport, TestTool tool, Duration heartbeatInterval) {
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(tool);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "workspace",
            "environment",
            "daemon",
            heartbeatInterval,
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10)),
        transport,
        registry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor());
  }

  private DaemonEnvelope invoke(String invocationId, long sequence) {
    return invoke(invocationId, sequence, "test");
  }

  private DaemonEnvelope invoke(String invocationId, long sequence, String toolName) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_1,
        DaemonMessageType.INVOKE,
        "workspace",
        "environment",
        invocationId,
        sequence,
        "{\"toolName\":\"" + toolName + "\",\"arguments\":{},\"timeoutMillis\":1000}");
  }

  private DaemonEnvelope cancel(String invocationId, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_1,
        DaemonMessageType.CANCEL,
        "workspace",
        "environment",
        invocationId,
        sequence,
        "{}");
  }

  private void assertMessageTypes(List<DaemonEnvelope> envelopes, DaemonMessageType... expected) {
    assertEquals(
        List.of(expected), envelopes.stream().map(DaemonEnvelope::messageType).toList());
  }

  private final class FakeTransport implements DaemonTransport {

    private final Semaphore connections = new Semaphore(0);
    private final LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private volatile DaemonTransportListener listener;
    private volatile FakeConnection connection;

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      this.listener = listener;
      connection = new FakeConnection();
      connections.release();
      return CompletableFuture.completedFuture(connection);
    }

    private void receive(DaemonEnvelope envelope) {
      listener.onMessage(codec.encode(envelope));
    }

    private void disconnect() {
      connection.open = false;
      listener.onDisconnected(null);
    }

    private void awaitConnections(int expected) throws InterruptedException {
      assertTrue(connections.tryAcquire(expected, 1, TimeUnit.SECONDS));
    }

    private List<DaemonEnvelope> takeMessages(int count) throws InterruptedException {
      List<DaemonEnvelope> messages = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String message = sent.poll(1, TimeUnit.SECONDS);
        assertTrue(message != null, "expected daemon message " + (index + 1));
        messages.add(codec.decode(message));
      }
      return messages;
    }

    private DaemonEnvelope takeNextMessage() throws InterruptedException {
      String message = sent.poll(1, TimeUnit.SECONDS);
      assertTrue(message != null, "expected daemon message");
      return codec.decode(message);
    }

    private boolean hasMessages() {
      return !sent.isEmpty();
    }

    private final class FakeConnection implements DaemonConnection {

      private volatile boolean open = true;

      @Override
      public CompletionStage<Void> sendText(String message) {
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

  private static final class TestTool implements Tool {

    private final ToolDescriptor descriptor =
        new ToolDescriptor(
            "test",
            "1.0.0",
            "test tool",
            null,
            new ToolParamsSchema("test arguments", Map.of(), Set.of(), false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(10));
    private final AtomicInteger executions = new AtomicInteger();
    private final TestHandle handle = new TestHandle();
    private volatile ToolExecutionListener listener;

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
      executions.incrementAndGet();
      this.listener = listener;
      return handle;
    }

    private void partial(ToolResult result) {
      listener.onPartial(result);
    }

    private void complete(ToolResult result) {
      listener.onComplete(result);
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
