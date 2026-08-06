package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolArgumentAliases;
import fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalStart;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkill;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.daemon.transport.JdkWebSocketTransport;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Environment Daemon 的连接、协议和本地 Tool SPI 执行基座。
 *
 * <p>Invocation journal 是进程内去重事实源；WebSocket 仅传递消息。连接断开后 Daemon 会重新握手；若 gateway 再次发送相同
 * invocationId，RUNNING/terminal journal 条目分别重放 STARTED/terminal。
 */
public final class DaemonRuntime implements AutoCloseable {

  private final DaemonConfig config;
  private final EnvironmentId environmentId;
  private final DaemonTransport transport;
  private final DaemonToolRegistry toolRegistry;
  private final DaemonSkillRegistry skillRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final ResourceStore resourceStore;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillsCodec skillsCodec = new DaemonSkillsCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final AtomicLong outboundSequence = new AtomicLong();
  private final AtomicReference<ActiveConnection> activeConnection = new AtomicReference<>();
  private final AtomicLong connectionGeneration = new AtomicLong();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean connecting = new AtomicBoolean();
  private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
  private final ConcurrentHashMap<String, RunningInvocation> running = new ConcurrentHashMap<>();
  private final Object lifecycleLock = new Object();
  private final Object reconnectLock = new Object();

  private volatile DaemonRuntimeState state = DaemonRuntimeState.STOPPED;
  private Duration nextReconnectDelay;

  /**
   * 使用 JDK WebSocket transport 和内存 journal 创建生产运行时，无 resource store；遇到 resource/binary 工具结果将确定性收敛为
   * FAILED。
   */
  public DaemonRuntime(
      DaemonConfig config,
      EnvironmentId environmentId,
      DaemonToolRegistry toolRegistry,
      DaemonSkillRegistry skillRegistry) {
    this(
        config,
        environmentId,
        new JdkWebSocketTransport(config.gatewayUri()),
        toolRegistry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        null,
        true);
  }

  /**
   * 使用 JDK WebSocket transport、内存 journal 和指定 resource store 创建生产运行时。
   *
   * <p>resource store 用于在发端读取/写入本地 resource 字节：{@code ResourceToolContent} 经其读取复核后写 wire， {@code
   * BinaryToolContent} 先落盘再编码。通常与 coding 工具的 {@link ResourceStore} 共享同一实例。当 {@code resourceStore} 为
   * {@code null} 时，遇到 resource/binary 工具结果会确定性收敛为 FAILED 而不会发送无法被接收端 解析的内容。
   */
  public DaemonRuntime(
      DaemonConfig config,
      EnvironmentId environmentId,
      DaemonToolRegistry toolRegistry,
      DaemonSkillRegistry skillRegistry,
      ResourceStore resourceStore) {
    this(
        config,
        environmentId,
        new JdkWebSocketTransport(config.gatewayUri()),
        toolRegistry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        resourceStore,
        true);
  }

  /** 使用可替换 transport 和 journal 创建运行时，便于协议集成测试或持久化替换。 */
  DaemonRuntime(
      DaemonConfig config,
      EnvironmentId environmentId,
      DaemonTransport transport,
      DaemonToolRegistry toolRegistry,
      DaemonSkillRegistry skillRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler) {
    this(
        config,
        environmentId,
        transport,
        toolRegistry,
        skillRegistry,
        journal,
        scheduler,
        null,
        false);
  }

  /** 全参数运行时；{@code resourceStore} 可为 {@code null} 以强制对所有 resource/binary 工具结果返回 FAILED。 */
  DaemonRuntime(
      DaemonConfig config,
      EnvironmentId environmentId,
      DaemonTransport transport,
      DaemonToolRegistry toolRegistry,
      DaemonSkillRegistry skillRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ResourceStore resourceStore) {
    this(
        config,
        environmentId,
        transport,
        toolRegistry,
        skillRegistry,
        journal,
        scheduler,
        resourceStore,
        false);
  }

  private DaemonRuntime(
      DaemonConfig config,
      EnvironmentId environmentId,
      DaemonTransport transport,
      DaemonToolRegistry toolRegistry,
      DaemonSkillRegistry skillRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ResourceStore resourceStore,
      boolean requireFixedToolCatalog) {
    this.config = Objects.requireNonNull(config, "config");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.resourceStore = resourceStore;
    this.nextReconnectDelay = config.initialReconnectDelay();
    if (requireFixedToolCatalog
        && !List.copyOf(toolRegistry.descriptors()).equals(EnvironmentToolCatalog.descriptors())) {
      throw new IllegalStateException("daemon tool registry does not match EnvironmentToolCatalog");
    }
    toolRegistry.freeze();
  }

  /** 启动连接生命周期并立即尝试建立 WebSocket。重复调用无副作用。 */
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get() || !started.compareAndSet(false, true)) {
        return;
      }
      state = DaemonRuntimeState.DISCONNECTED;
      try {
        ScheduledFuture<?> heartbeat =
            scheduler.scheduleWithFixedDelay(
                this::sendHeartbeat,
                config.heartbeatInterval().toMillis(),
                config.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        if (!scheduleReconnect(Duration.ZERO)) {
          heartbeat.cancel(false);
          started.set(false);
          state = DaemonRuntimeState.STOPPED;
        }
      } catch (RejectedExecutionException ignored) {
        started.set(false);
        state = DaemonRuntimeState.STOPPED;
      }
    }
  }

  /** 返回当前连接生命周期状态，仅用于运行状态观测。 */
  public DaemonRuntimeState state() {
    return state;
  }

  private boolean scheduleReconnect(Duration delay) {
    if (!started.get() || !reconnectScheduled.compareAndSet(false, true)) {
      return started.get();
    }
    try {
      scheduler.schedule(
          () -> {
            reconnectScheduled.set(false);
            connect();
          },
          delay.toMillis(),
          TimeUnit.MILLISECONDS);
      return true;
    } catch (RejectedExecutionException ignored) {
      reconnectScheduled.set(false);
      return false;
    }
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
                ActiveConnection active = new ActiveConnection(generation, connection);
                synchronized (active) {
                  activeConnection.set(active);
                  nextReconnectDelay = config.initialReconnectDelay();
                  if (sendHello(active)) {
                    active.markHelloSent();
                  }
                }
              });
    } catch (RuntimeException error) {
      connecting.set(false);
      handleDisconnected(generation);
    }
  }

  private void handleDisconnected(long generation) {
    ActiveConnection connection = activeConnection.get();
    if (connection != null) {
      if (connection.generation() != generation
          || !activeConnection.compareAndSet(connection, null)) {
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

  private boolean sendHello(ActiveConnection connection) {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("daemonId", config.daemonId());
    payload.put("protocolVersion", DaemonProtocol.VERSION_2);
    payload.put("toolCatalogVersion", EnvironmentToolCatalog.version());
    payload.put("gatewayToken", config.gatewayToken());
    return sendOn(connection, DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private boolean sendReady(ActiveConnection connection) {
    return sendOn(
        connection,
        DaemonMessageType.READY,
        null,
        skillsCodec.encode(List.copyOf(skillRegistry.descriptors())));
  }

  private void sendHeartbeat() {
    if (state == DaemonRuntimeState.READY) {
      send(DaemonMessageType.HEARTBEAT, null, "{}");
    }
  }

  private void onMessage(long generation, String rawMessage) {
    ActiveConnection connection = activeConnection.get();
    if (connection == null || connection.generation() != generation) {
      return;
    }
    try {
      DaemonEnvelope envelope = envelopeCodec.decode(rawMessage);
      verifyScope(envelope);
      InboundEnvelopeIdentity identity =
          InboundEnvelopeIdentity.from(envelope, envelopeCodec.readPayload(envelope));
      switch (envelope.messageType()) {
        case INVOKE -> {
          requireInvocationId(envelope);
          InvokePayload payload = readInvokePayload(envelope);
          connection.acceptInboundEnvelope(identity);
          processInvoke(connection, envelope, payload);
        }
        case CANCEL -> {
          requireInvocationId(envelope);
          connection.acceptInboundEnvelope(identity);
          processCancel(connection, envelope);
        }
        case LOAD_SKILL -> {
          requireInvocationId(envelope);
          connection.acceptInboundEnvelope(identity);
          processLoadSkill(connection, envelope);
        }
        case WELCOME -> {
          connection.acceptInboundEnvelope(identity);
          handleWelcome(connection, envelope);
        }
        case ACK, ERROR -> {
          connection.acceptInboundEnvelope(identity);
          // Gateway protocol messages do not alter Daemon invocation facts.
        }
        default -> throw new DaemonProtocolException(
            "unexpected inbound messageType: " + envelope.messageType());
      }
    } catch (IllegalArgumentException error) {
      sendOn(connection, DaemonMessageType.ERROR, null, errorPayload(error));
    }
  }

  private void handleWelcome(ActiveConnection connection, DaemonEnvelope envelope) {
    synchronized (connection) {
      if (!connection.helloSent()) {
        throw new DaemonProtocolException("WELCOME requires a preceding HELLO");
      }
      if (!connection.markWelcomed()) {
        throw new DaemonProtocolException("WELCOME may only be received once per connection");
      }
      if (!envelopeCodec.readPayload(envelope).isEmpty()) {
        throw new DaemonProtocolException("WELCOME payload must be empty");
      }
      if (sendReady(connection) && activeConnection.get() == connection) {
        connection.markReady();
        state = DaemonRuntimeState.READY;
      }
    }
  }

  private void verifyScope(DaemonEnvelope envelope) {
    if (!environmentId.equals(envelope.environmentId())
        || !config.environmentName().equals(envelope.environmentName())) {
      throw new DaemonProtocolException(
          "envelope environmentId or environmentName does not match daemon");
    }
  }

  private void processInvoke(
      ActiveConnection connection, DaemonEnvelope envelope, InvokePayload payload) {
    sendAck(connection, envelope.sequence());
    handleInvoke(connection, envelope, payload);
  }

  private void handleInvoke(
      ActiveConnection connection, DaemonEnvelope envelope, InvokePayload payload) {
    if (!started.get()) {
      return;
    }
    DaemonInvocationJournalStart start = journal.start(envelope.invocationId());
    if (!start.created()) {
      replay(start.entry(), envelope.invocationId());
      return;
    }

    try {
      Tool tool =
          toolRegistry
              .find(payload.toolName())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "unknown environment tool: " + payload.toolName()));
      ToolDescriptor descriptor = tool.descriptor();
      if (!descriptor.version().equals(payload.toolVersion())) {
        throw new IllegalArgumentException(
            "toolVersion does not match environment descriptor: " + payload.toolVersion());
      }
      Duration timeout = resolveTimeout(payload.timeout(), descriptor);
      String argumentsJson =
          CodingToolArgumentAliases.normalize(payload.toolName(), payload.argumentsJson());
      ToolExecutionRequest request =
          new ToolExecutionRequest(
              descriptor,
              new ToolCall(envelope.invocationId(), payload.toolName(), argumentsJson),
              timeout);
      RunningInvocation invocation = new RunningInvocation(envelope.invocationId());
      running.put(envelope.invocationId(), invocation);
      if (!started.get() || !isRunning(envelope.invocationId()) || !invocation.begin()) {
        running.remove(envelope.invocationId(), invocation);
        if (!started.get()) {
          journal.complete(
              envelope.invocationId(),
              new DaemonTerminalMessage(
                  DaemonMessageType.CANCELLED, "{\"reason\":\"daemon stopped\"}"));
        }
        return;
      }
      sendOn(connection, DaemonMessageType.STARTED, envelope.invocationId(), "{}");
      ToolExecutionHandle handle = tool.execute(request, new InvocationListener(invocation));
      invocation.setHandle(Objects.requireNonNull(handle, "tool execution handle"));
      scheduleTimeout(invocation, timeout);
    } catch (RuntimeException error) {
      terminal(
          envelope.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error)),
          false);
    }
  }

  private void processCancel(ActiveConnection connection, DaemonEnvelope envelope) {
    sendAck(connection, envelope.sequence());
    handleCancel(envelope);
  }

  private void processLoadSkill(ActiveConnection connection, DaemonEnvelope envelope) {
    sendAck(connection, envelope.sequence());
    handleLoadSkill(connection, envelope);
  }

  private void handleLoadSkill(ActiveConnection connection, DaemonEnvelope envelope) {
    DaemonSkillLoadCodec.LoadSkillRequest request;
    try {
      request = skillLoadCodec.decodeRequest(envelope.payloadJson());
    } catch (DaemonProtocolException error) {
      sendOn(connection, DaemonMessageType.ERROR, envelope.invocationId(), errorPayload(error));
      return;
    }
    Optional<DaemonSkill> skill = skillRegistry.find(request.name());
    if (skill.isEmpty()) {
      sendOn(
          connection,
          DaemonMessageType.SKILL_LOAD_FAILED,
          envelope.invocationId(),
          skillLoadCodec.encodeFailed(
              new DaemonSkillLoadCodec.SkillLoadFailed(
                  request.name(), "unknown skill: " + request.name())));
      return;
    }
    sendOn(
        connection,
        DaemonMessageType.SKILL_LOADED,
        envelope.invocationId(),
        skillLoadCodec.encodeLoaded(
            new DaemonSkillLoadCodec.SkillLoaded(skill.get().name(), skill.get().body())));
  }

  private void handleCancel(DaemonEnvelope envelope) {
    Optional<DaemonInvocationJournalEntry> entry = journal.find(envelope.invocationId());
    if (entry.isEmpty()) {
      return;
    }
    if (entry.get().state().isTerminal()) {
      replay(entry.get(), envelope.invocationId());
      return;
    }
    terminal(
        envelope.invocationId(),
        new DaemonTerminalMessage(DaemonMessageType.CANCELLED, "{\"reason\":\"cancelled\"}"),
        true);
  }

  private void replay(DaemonInvocationJournalEntry entry, String invocationId) {
    if (entry.state() == DaemonInvocationState.RUNNING) {
      send(DaemonMessageType.STARTED, invocationId, "{\"replayed\":true}");
      return;
    }
    DaemonTerminalMessage terminal = entry.terminalMessage();
    send(terminal.messageType(), invocationId, terminal.payloadJson());
  }

  private void terminal(String invocationId, DaemonTerminalMessage message, boolean cancelHandle) {
    RunningInvocation invocation = running.get(invocationId);
    if (invocation != null && !invocation.claimTerminal(cancelHandle)) {
      return;
    }
    if (journal.complete(invocationId, message)) {
      if (invocation == null) {
        running.remove(invocationId);
      } else {
        running.remove(invocationId, invocation);
      }
      send(message.messageType(), invocationId, message.payloadJson());
    }
  }

  private void scheduleTimeout(RunningInvocation invocation, Duration timeout) {
    if (!isRunning(invocation.invocationId()) || invocation.isTerminal()) {
      return;
    }
    try {
      ScheduledFuture<?> deadline =
          scheduler.schedule(
              () ->
                  terminal(
                      invocation.invocationId(),
                      new DaemonTerminalMessage(
                          DaemonMessageType.FAILED,
                          "{\"message\":\"tool execution timed out after "
                              + timeout.toMillis()
                              + "ms\"}"),
                      true),
              timeout.toNanos(),
              TimeUnit.NANOSECONDS);
      invocation.setDeadline(deadline);
    } catch (RejectedExecutionException ignored) {
      // close() has already recorded the cancellation and stopped the scheduler.
    }
  }

  /**
   * 0 timeoutMillis 表示不覆盖 descriptor；descriptor 未设置 deadline 时回退 daemon 默认值，确保 每次 invocation 都有有效
   * deadline。
   */
  private Duration resolveTimeout(Duration requestedTimeout, ToolDescriptor descriptor) {
    if (!requestedTimeout.isZero()) {
      return requestedTimeout;
    }
    return descriptor.timeout().isZero() ? config.defaultToolTimeout() : descriptor.timeout();
  }

  private InvokePayload readInvokePayload(DaemonEnvelope envelope) {
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    JsonNode arguments = payload.get("arguments");
    if (arguments == null || !arguments.isObject()) {
      throw new DaemonProtocolException("INVOKE payload.arguments must be a JSON object");
    }
    long timeoutMillis = optionalNonNegativeLong(payload, "timeoutMillis", 0);
    return new InvokePayload(
        requiredPayloadText(payload, "toolName"),
        requiredPayloadText(payload, "toolVersion"),
        envelopeCodec.writeJson(arguments),
        Duration.ofMillis(timeoutMillis));
  }

  private long optionalNonNegativeLong(ObjectNode payload, String fieldName, long defaultValue) {
    JsonNode value = payload.get(fieldName);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new DaemonProtocolException(
          "INVOKE payload." + fieldName + " must be a non-negative long");
    }
    return value.longValue();
  }

  private void requireInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null || envelope.invocationId().isBlank()) {
      throw new DaemonProtocolException(
          envelope.messageType() + " requires a non-blank invocationId");
    }
  }

  private String requiredPayloadText(ObjectNode payload, String fieldName) {
    JsonNode value = payload.get(fieldName);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new DaemonProtocolException(
          "INVOKE payload." + fieldName + " must be a non-blank string");
    }
    return value.textValue();
  }

  private void sendAck(ActiveConnection connection, long acknowledgedSequence) {
    sendOn(
        connection,
        DaemonMessageType.ACK,
        null,
        "{\"acknowledgedSequence\":" + acknowledgedSequence + "}");
  }

  private void send(DaemonMessageType messageType, String invocationId, String payloadJson) {
    ActiveConnection connection = activeConnection.get();
    if (connection == null || !connection.isReady()) {
      return;
    }
    sendOn(connection, messageType, invocationId, payloadJson);
  }

  private boolean sendOn(
      ActiveConnection connection,
      DaemonMessageType messageType,
      String invocationId,
      String payloadJson) {
    if (activeConnection.get() != connection || !connection.connection().isOpen()) {
      return false;
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
    return true;
  }

  private DaemonEnvelope envelope(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        messageType,
        environmentId,
        config.environmentName(),
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

  @Override
  public void close() {
    ActiveConnection connection;
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      started.set(false);
      state = DaemonRuntimeState.STOPPED;
      connection = activeConnection.getAndSet(null);
    }
    if (connection != null) {
      connection.connection().close();
    }
    running
        .values()
        .forEach(
            invocation ->
                terminal(
                    invocation.invocationId(),
                    new DaemonTerminalMessage(
                        DaemonMessageType.CANCELLED, "{\"reason\":\"daemon stopped\"}"),
                    true));
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
      if (!isRunning(invocation.invocationId())
          || !matchesInvocation(partial, invocation.invocationId())) {
        return;
      }
      try {
        send(
            DaemonMessageType.PARTIAL,
            invocation.invocationId(),
            resultCodec.encodePartial(partial, resourceWriter()));
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "partial");
      }
    }

    @Override
    public void onComplete(ToolResult result) {
      if (!matchesInvocation(result, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"tool result toolCallId does not match invocationId\"}"),
            false);
        return;
      }
      String payload;
      try {
        payload = resultCodec.encodeCompleted(result, resourceWriter());
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "complete");
        return;
      }
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.COMPLETED, payload),
          false);
    }

    @Override
    public void onError(Throwable error) {
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error)),
          false);
    }
  }

  /** resource 读取/落盘或编码失败时，确定性收敛为 FAILED，避免让 callback 漏掉终态。允许已有 journal 记录为 RUNNING 时覆盖。 */
  private void failResultEncoding(String invocationId, RuntimeException error, String phase) {
    String message = "cannot " + phase + " tool result: " + error.getMessage();
    terminal(
        invocationId,
        new DaemonTerminalMessage(
            DaemonMessageType.FAILED, errorPayload(new IllegalStateException(message, error))),
        false);
  }

  /** 提供给结果 codec 的 resource 读写 SPI；未配置 store 时对任何 resource/binary 内容确定性失败。 */
  private DaemonResourceStore resourceWriter() {
    ResourceStore store = this.resourceStore;
    return new DaemonResourceStore() {
      @Override
      public ResourceRef store(byte[] bytes, String mediaType) throws IOException {
        if (store == null) {
          throw new IllegalStateException("resource store is not configured");
        }
        return store.store(bytes, mediaType);
      }

      @Override
      public byte[] read(ResourceRef ref) throws IOException {
        if (store == null) {
          throw new IllegalStateException("resource store is not configured");
        }
        return store.read(ref);
      }
    };
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
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicReference<ToolExecutionHandle> handle = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

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

    private boolean claimTerminal(boolean cancelHandle) {
      if (!terminal.compareAndSet(false, true)) {
        return false;
      }
      ScheduledFuture<?> timeout = deadline.getAndSet(null);
      if (timeout != null) {
        timeout.cancel(false);
      }
      if (cancelHandle) {
        cancel();
      }
      return true;
    }

    private boolean isTerminal() {
      return terminal.get();
    }

    private void setDeadline(ScheduledFuture<?> value) {
      if (!deadline.compareAndSet(null, value) || terminal.get()) {
        value.cancel(false);
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

  private static final class ActiveConnection {

    private final long generation;
    private final DaemonConnection connection;
    private final AtomicBoolean helloSent = new AtomicBoolean();
    private final AtomicBoolean welcomed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private InboundEnvelopeIdentity lastInboundIdentity;

    private ActiveConnection(long generation, DaemonConnection connection) {
      this.generation = generation;
      this.connection = connection;
    }

    private long generation() {
      return generation;
    }

    private DaemonConnection connection() {
      return connection;
    }

    private void markReady() {
      ready.set(true);
    }

    private void markHelloSent() {
      helloSent.set(true);
    }

    private boolean helloSent() {
      return helloSent.get();
    }

    private boolean markWelcomed() {
      return welcomed.compareAndSet(false, true);
    }

    private boolean isReady() {
      return ready.get() && connection.isOpen();
    }

    private synchronized void acceptInboundEnvelope(InboundEnvelopeIdentity identity) {
      if (lastInboundIdentity == null
          || identity.sequence() == lastInboundIdentity.sequence() + 1) {
        lastInboundIdentity = identity;
        return;
      }
      if (identity.sequence() == lastInboundIdentity.sequence()) {
        if (identity.equals(lastInboundIdentity)) {
          return;
        }
        throw new DaemonProtocolException(
            "inbound sequence reused by a conflicting envelope: " + identity.sequence());
      }
      if (identity.sequence() < lastInboundIdentity.sequence()) {
        throw new DaemonProtocolException(
            "inbound sequence moved backwards: " + identity.sequence());
      }
      throw new DaemonProtocolException(
          "inbound sequence must immediately follow "
              + lastInboundIdentity.sequence()
              + ": "
              + identity.sequence());
    }
  }

  private record InboundEnvelopeIdentity(
      int protocolVersion,
      DaemonMessageType messageType,
      EnvironmentId environmentId,
      String environmentName,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentId(),
          envelope.environmentName(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }

  private record InvokePayload(
      String toolName, String toolVersion, String argumentsJson, Duration timeout) {}
}
