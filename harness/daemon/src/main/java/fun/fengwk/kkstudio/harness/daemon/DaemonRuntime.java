package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalStart;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.daemon.transport.JdkWebSocketTransport;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Environment Daemon 的连接、协议和本地 Tool SPI 执行基座。
 *
 * <p>Invocation journal 是去重事实源；WebSocket 仅传递消息。因此连接断开后，Daemon 可以连接到
 * 任意 gateway 并通过 READY/PULL 重新获取未完成调用。
 */
public final class DaemonRuntime implements AutoCloseable {

  private final DaemonConfig config;
  private final DaemonTransport transport;
  private final DaemonToolRegistry toolRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final AtomicLong outboundSequence = new AtomicLong();
  private final AtomicReference<ActiveConnection> activeConnection = new AtomicReference<>();
  private final AtomicLong connectionGeneration = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean connecting = new AtomicBoolean();
  private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
  private final ConcurrentHashMap<String, RunningInvocation> running = new ConcurrentHashMap<>();
  private final Object reconnectLock = new Object();

  private volatile DaemonRuntimeState state = DaemonRuntimeState.STOPPED;
  private Duration nextReconnectDelay;

  /** 使用 JDK WebSocket transport 和内存 journal 创建生产运行时。 */
  public DaemonRuntime(DaemonConfig config, DaemonToolRegistry toolRegistry) {
    this(
        config,
        new JdkWebSocketTransport(config.gatewayUri()),
        toolRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor());
  }

  /** 使用可替换 transport 和 journal 创建运行时，便于协议集成测试或持久化替换。 */
  public DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonToolRegistry toolRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler) {
    this.config = Objects.requireNonNull(config, "config");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.nextReconnectDelay = config.initialReconnectDelay();
  }

  /** 启动连接生命周期并立即尝试建立 WebSocket。重复调用无副作用。 */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    state = DaemonRuntimeState.DISCONNECTED;
    scheduler.scheduleWithFixedDelay(
        this::sendHeartbeat,
        config.heartbeatInterval().toMillis(),
        config.heartbeatInterval().toMillis(),
        TimeUnit.MILLISECONDS);
    scheduleReconnect(Duration.ZERO);
  }

  /** 返回当前连接生命周期状态，仅用于运行状态观测。 */
  public DaemonRuntimeState state() {
    return state;
  }

  private void scheduleReconnect(Duration delay) {
    if (!started.get() || !reconnectScheduled.compareAndSet(false, true)) {
      return;
    }
    scheduler.schedule(
        () -> {
          reconnectScheduled.set(false);
          connect();
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private void connect() {
    if (!started.get() || !connecting.compareAndSet(false, true)) {
      return;
    }
    state = DaemonRuntimeState.CONNECTING;
    long generation = connectionGeneration.incrementAndGet();
    DaemonTransportListener listener = new RuntimeTransportListener(generation);
    try {
      transport
          .connect(listener)
          .whenComplete(
              (connection, error) -> {
                connecting.set(false);
                if (error != null || connection == null) {
                  handleDisconnected(generation);
                  return;
                }
                if (!started.get() || generation != connectionGeneration.get()) {
                  connection.close();
                  return;
                }
                activeConnection.set(new ActiveConnection(generation, connection));
                nextReconnectDelay = config.initialReconnectDelay();
                state = DaemonRuntimeState.READY;
                sendHello();
                sendCapabilities();
                sendReady();
              });
    } catch (RuntimeException error) {
      connecting.set(false);
      handleDisconnected(generation);
    }
  }

  private void handleDisconnected(long generation) {
    ActiveConnection connection = activeConnection.get();
    if (connection != null) {
      if (connection.generation() != generation || !activeConnection.compareAndSet(connection, null)) {
        return;
      }
    } else if (state != DaemonRuntimeState.CONNECTING) {
      return;
    }
    if (!started.get() || generation != connectionGeneration.get()) {
      return;
    }
    state = DaemonRuntimeState.DISCONNECTED;
    Duration delay;
    synchronized (reconnectLock) {
      delay = nextReconnectDelay;
      nextReconnectDelay = doubled(nextReconnectDelay, config.maxReconnectDelay());
    }
    scheduleReconnect(delay);
  }

  private Duration doubled(Duration value, Duration maximum) {
    try {
      Duration doubled = value.multipliedBy(2);
      return doubled.compareTo(maximum) > 0 ? maximum : doubled;
    } catch (ArithmeticException error) {
      return maximum;
    }
  }

  private void sendHello() {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("daemonId", config.daemonId());
    payload.put("protocolVersion", DaemonProtocol.VERSION_1);
    send(DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private void sendCapabilities() {
    ObjectNode payload = envelopeCodec.createPayload();
    ArrayNode tools = payload.putArray("tools");
    for (ToolDescriptor descriptor : toolRegistry.descriptors()) {
      ObjectNode tool = tools.addObject();
      tool.put("name", descriptor.name());
      tool.put("version", descriptor.version());
      tool.put("description", descriptor.description());
      tool.put("executionMode", descriptor.executionMode().name());
      tool.put("sideEffect", descriptor.sideEffect().name());
      tool.put("timeoutMillis", descriptor.timeout().toMillis());
    }
    send(DaemonMessageType.CAPABILITIES, null, envelopeCodec.writeJson(payload));
  }

  private void sendReady() {
    send(DaemonMessageType.READY, null, "{\"pull\":true}");
  }

  private void sendHeartbeat() {
    if (state == DaemonRuntimeState.READY) {
      send(DaemonMessageType.HEARTBEAT, null, "{}");
    }
  }

  private void onMessage(long generation, String rawMessage) {
    if (!isCurrent(generation)) {
      return;
    }
    try {
      DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
      verifyScope(envelope);
      switch (envelope.messageType()) {
        case INVOKE -> handleInvoke(envelope);
        case CANCEL -> handleCancel(envelope);
        case WELCOME, ACK, ERROR -> {
          // Cloud control messages do not alter local invocation facts.
        }
        default -> throw new DaemonProtocolException("unexpected inbound messageType: " + envelope.messageType());
      }
    } catch (IllegalArgumentException error) {
      send(DaemonMessageType.ERROR, null, errorPayload(error));
    }
  }

  private void verifyScope(DaemonEnvelope envelope) {
    if (!config.workspaceId().equals(envelope.workspaceId())
        || !config.environmentId().equals(envelope.environmentId())) {
      throw new DaemonProtocolException("envelope workspaceId/environmentId does not match daemon");
    }
  }

  private void handleInvoke(DaemonEnvelope envelope) {
    sendAck(envelope.sequence());
    DaemonInvocationJournalStart start = journal.start(envelope.invocationId());
    if (!start.created()) {
      replay(start.entry(), envelope.invocationId());
      return;
    }

    try {
      InvokePayload payload = readInvokePayload(envelope);
      Tool tool =
          toolRegistry
              .find(payload.toolName())
              .orElseThrow(
                  () -> new IllegalArgumentException("unknown local tool: " + payload.toolName()));
      ToolDescriptor descriptor = tool.descriptor();
      ToolExecutionRequest request =
          new ToolExecutionRequest(
              descriptor,
              new ToolCall(envelope.invocationId(), payload.toolName(), payload.argumentsJson()),
              payload.timeout());
      RunningInvocation invocation = new RunningInvocation(envelope.invocationId());
      running.put(envelope.invocationId(), invocation);
      if (!isRunning(envelope.invocationId()) || !invocation.begin()) {
        running.remove(envelope.invocationId(), invocation);
        return;
      }
      send(DaemonMessageType.STARTED, envelope.invocationId(), "{}");
      ToolExecutionHandle handle = tool.execute(request, new InvocationListener(invocation));
      invocation.setHandle(Objects.requireNonNull(handle, "tool execution handle"));
    } catch (RuntimeException error) {
      terminal(
          envelope.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error)));
    }
  }

  private void handleCancel(DaemonEnvelope envelope) {
    sendAck(envelope.sequence());
    Optional<DaemonInvocationJournalEntry> entry = journal.find(envelope.invocationId());
    if (entry.isEmpty()) {
      return;
    }
    if (entry.get().state().isTerminal()) {
      replay(entry.get(), envelope.invocationId());
      return;
    }
    RunningInvocation invocation = running.remove(envelope.invocationId());
    if (invocation != null) {
      invocation.cancel();
    }
    terminal(
        envelope.invocationId(),
        new DaemonTerminalMessage(DaemonMessageType.CANCELLED, "{\"reason\":\"cancelled\"}"));
  }

  private void replay(DaemonInvocationJournalEntry entry, String invocationId) {
    if (entry.state() == DaemonInvocationState.RUNNING) {
      send(DaemonMessageType.STARTED, invocationId, "{\"replayed\":true}");
      return;
    }
    DaemonTerminalMessage terminal = entry.terminalMessage();
    send(terminal.messageType(), invocationId, terminal.payloadJson());
  }

  private void terminal(String invocationId, DaemonTerminalMessage message) {
    if (journal.complete(invocationId, message)) {
      running.remove(invocationId);
      send(message.messageType(), invocationId, message.payloadJson());
    }
  }

  private InvokePayload readInvokePayload(DaemonEnvelope envelope) {
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    JsonNode arguments = payload.get("arguments");
    if (arguments == null || !arguments.isObject()) {
      throw new DaemonProtocolException("INVOKE payload.arguments must be a JSON object");
    }
    long timeoutMillis = optionalNonNegativeLong(payload, "timeoutMillis", config.defaultToolTimeout().toMillis());
    return new InvokePayload(
        requiredPayloadText(payload, "toolName"),
        envelopeCodec.writeJson(arguments),
        Duration.ofMillis(timeoutMillis));
  }

  private long optionalNonNegativeLong(ObjectNode payload, String fieldName, long defaultValue) {
    JsonNode value = payload.get(fieldName);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new DaemonProtocolException("INVOKE payload." + fieldName + " must be a non-negative long");
    }
    return value.longValue();
  }

  private String requiredPayloadText(ObjectNode payload, String fieldName) {
    JsonNode value = payload.get(fieldName);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException("INVOKE payload." + fieldName + " must be a non-blank string");
    }
    return value.textValue();
  }

  private void sendAck(long acknowledgedSequence) {
    send(DaemonMessageType.ACK, null, "{\"acknowledgedSequence\":" + acknowledgedSequence + "}");
  }

  private void send(DaemonMessageType messageType, String invocationId, String payloadJson) {
    ActiveConnection connection = activeConnection.get();
    if (connection == null || !connection.connection().isOpen()) {
      return;
    }
    DaemonEnvelope envelope = envelope(messageType, invocationId, payloadJson);
    connection
        .connection()
        .sendText(envelopeCodec.encode(envelope))
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                handleDisconnected(connection.generation());
              }
            });
  }

  private DaemonEnvelope envelope(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_1,
        messageType,
        config.workspaceId(),
        config.environmentId(),
        invocationId,
        outboundSequence.getAndIncrement(),
        payloadJson);
  }

  private String errorPayload(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getClass().getSimpleName();
    }
    return "{\"message\":" + quote(message) + "}";
  }

  private String quote(String value) {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("value", value);
    return envelopeCodec.writeJson(payload.get("value"));
  }

  private boolean isCurrent(long generation) {
    ActiveConnection connection = activeConnection.get();
    return connection != null && connection.generation() == generation;
  }

  @Override
  public void close() {
    if (!started.compareAndSet(true, false)) {
      return;
    }
    state = DaemonRuntimeState.STOPPED;
    ActiveConnection connection = activeConnection.getAndSet(null);
    if (connection != null) {
      connection.connection().close();
    }
    running.values().forEach(RunningInvocation::cancel);
    running.clear();
    scheduler.shutdownNow();
    transport.close();
  }

  private final class InvocationListener implements ToolExecutionListener {

    private final RunningInvocation invocation;

    private InvocationListener(RunningInvocation invocation) {
      this.invocation = invocation;
    }

    @Override
    public void onPartial(ToolResult partial) {
      if (isRunning(invocation.invocationId()) && matchesInvocation(partial, invocation.invocationId())) {
        send(DaemonMessageType.PARTIAL, invocation.invocationId(), resultPayload(partial));
      }
    }

    @Override
    public void onComplete(ToolResult result) {
      if (!matchesInvocation(result, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"tool result toolCallId does not match invocationId\"}"));
        return;
      }
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.COMPLETED, resultPayload(result)));
    }

    @Override
    public void onError(Throwable error) {
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error)));
    }
  }

  private boolean isRunning(String invocationId) {
    return journal
        .find(invocationId)
        .map(entry -> entry.state() == DaemonInvocationState.RUNNING)
        .orElse(false);
  }

  private boolean matchesInvocation(ToolResult result, String invocationId) {
    return result != null && invocationId.equals(result.toolCallId());
  }

  private String resultPayload(ToolResult result) {
    ObjectNode payload = envelopeCodec.createPayload();
    ObjectNode wireResult = payload.putObject("result");
    wireResult.put("toolCallId", result.toolCallId());
    wireResult.put("error", result.error());
    wireResult.set("details", envelopeCodec.readJson(result.detailsJson()));
    ArrayNode contents = wireResult.putArray("contents");
    for (ToolContent content : result.contents()) {
      writeContent(contents, content);
    }
    return envelopeCodec.writeJson(payload);
  }

  private void writeContent(ArrayNode contents, ToolContent content) {
    ObjectNode wireContent = contents.addObject();
    if (content instanceof TextToolContent text) {
      wireContent.put("type", "text");
      wireContent.put("text", text.text());
    } else if (content instanceof JsonToolContent json) {
      wireContent.put("type", "json");
      wireContent.set("json", envelopeCodec.readJson(json.json()));
    } else if (content instanceof ArtifactToolContent artifact) {
      wireContent.put("type", "artifact");
      wireContent.put("artifactId", artifact.artifact().artifactId());
      wireContent.put("mediaType", artifact.artifact().mediaType());
      wireContent.put("sizeBytes", artifact.artifact().sizeBytes());
    } else {
      throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
  }

  private final class RuntimeTransportListener implements DaemonTransportListener {

    private final long generation;

    private RuntimeTransportListener(long generation) {
      this.generation = generation;
    }

    @Override
    public void onMessage(String message) {
      DaemonRuntime.this.onMessage(generation, message);
    }

    @Override
    public void onDisconnected(Throwable cause) {
      handleDisconnected(generation);
    }
  }

  private static final class RunningInvocation {

    private final String invocationId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicReference<ToolExecutionHandle> handle = new AtomicReference<>();

    private RunningInvocation(String invocationId) {
      this.invocationId = invocationId;
    }

    private String invocationId() {
      return invocationId;
    }

    private boolean begin() {
      return !cancelled.get() && begun.compareAndSet(false, true);
    }

    private void setHandle(ToolExecutionHandle value) {
      handle.set(value);
      if (cancelled.get()) {
        value.cancel();
      }
    }

    private void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        ToolExecutionHandle value = handle.get();
        if (value != null) {
          value.cancel();
        }
      }
    }
  }

  private record ActiveConnection(long generation, DaemonConnection connection) {}

  private record InvokePayload(String toolName, String argumentsJson, Duration timeout) {}
}
