package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingCapabilities;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalStart;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpBridgeCapabilities;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerRegistry;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkill;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.daemon.transport.JdkWebSocketTransport;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryFailureCode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Environment Daemon 的连接、协议和本地 Environment Capability 执行基座。
 *
 * <p>Invocation journal 是进程内去重事实源；WebSocket 仅传递消息。连接断开后 Daemon 会重新握手；若 gateway 再次发送相同
 * invocationId，RUNNING/terminal journal 条目分别重放 STARTED/terminal。
 *
 * <p>Daemon 只拥有两个执行生命周期资源：单线程 scheduler 处理 heartbeat、reconnect 与 timeout，共享的
 * virtual-thread-per-task executor 处理 Coding/MCP 及目录浏览等阻塞调用。transport/JDK 内部线程不在该生命周期内。
 */
public final class DaemonRuntime implements AutoCloseable {

  private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
  private static final String FALLBACK_FAILURE_MESSAGE = "capability execution failed";

  private final DaemonConfig config;
  private final EnvironmentName environmentName;
  private final DaemonTransport transport;
  private final DaemonCapabilityRegistry capabilityRegistry;
  private final DaemonSkillRegistry skillRegistry;
  private final McpServerRegistry mcpRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService taskExecutor;
  private final ResourceStore resourceStore;
  private final DaemonEnvironmentInfo environmentInfo;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();
  private final DaemonDirectoryCodec directoryCodec = new DaemonDirectoryCodec();
  private final EnvironmentDirectoryBrowser directoryBrowser;
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
  private final CountDownLatch termination = new CountDownLatch(1);
  private volatile String failureReason;
  private Duration nextReconnectDelay;

  /**
   * 创建完整生产运行时。scheduler 与 virtual-thread-per-task executor 在注册 capability 前创建并注入，成功后由 runtime
   * 独占生命周期；任一步构造失败都会释放 transport、MCP registry 和已创建的执行资源。
   */
  public static DaemonRuntime create(
      DaemonConfig config,
      CodingToolsConfig toolsConfig,
      DaemonSkillRegistry skillRegistry,
      McpServerRegistry mcpRegistry) {
    Objects.requireNonNull(toolsConfig, "toolsConfig");
    Objects.requireNonNull(mcpRegistry, "mcpRegistry");
    return create(
        config,
        skillRegistry,
        mcpRegistry,
        toolsConfig.resourceStore(),
        (registry, executor, scheduler) -> {
          CodingCapabilities.registerAll(registry, toolsConfig, executor, scheduler);
          McpBridgeCapabilities.registerAll(registry, mcpRegistry, executor);
        });
  }

  static DaemonRuntime create(
      DaemonConfig config,
      DaemonSkillRegistry skillRegistry,
      McpServerRegistry mcpRegistry,
      ResourceStore resourceStore,
      CapabilityRegistrar registrar) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(skillRegistry, "skillRegistry");
    Objects.requireNonNull(mcpRegistry, "mcpRegistry");
    Objects.requireNonNull(registrar, "registrar");
    ScheduledThreadPoolExecutor scheduler = newScheduler();
    ExecutorService taskExecutor = null;
    DaemonTransport transport = null;
    boolean completed = false;
    try {
      taskExecutor = newTaskExecutor();
      transport = new JdkWebSocketTransport(config.gatewayUri());
      DaemonCapabilityRegistry capabilityRegistry = new DaemonCapabilityRegistry();
      registrar.register(capabilityRegistry, taskExecutor, scheduler);
      DaemonRuntime runtime =
          new DaemonRuntime(
              config,
              transport,
              capabilityRegistry,
              skillRegistry,
              mcpRegistry,
              new InMemoryDaemonInvocationJournal(),
              scheduler,
              taskExecutor,
              resourceStore,
              true);
      completed = true;
      return runtime;
    } finally {
      if (!completed) {
        closeQuietly(transport);
        closeQuietly(mcpRegistry);
        shutdownExecutors(scheduler, taskExecutor);
      }
    }
  }

  /** 使用可替换 transport、journal 与执行资源创建运行时；注入资源的生命周期移交给 runtime。 */
  DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonSkillRegistry skillRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor) {
    this(
        config,
        transport,
        capabilityRegistry,
        skillRegistry,
        McpServerRegistry.empty(),
        journal,
        scheduler,
        taskExecutor,
        null,
        false);
  }

  /** 全参数运行时；{@code resourceStore} 可为 {@code null} 以强制对 resource/binary 结果返回 FAILED。 */
  DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonSkillRegistry skillRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ResourceStore resourceStore) {
    this(
        config,
        transport,
        capabilityRegistry,
        skillRegistry,
        McpServerRegistry.empty(),
        journal,
        scheduler,
        taskExecutor,
        resourceStore,
        false);
  }

  /** 全参数运行时（含 MCP registry），供集成测试注入完整生命周期。 */
  DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonSkillRegistry skillRegistry,
      McpServerRegistry mcpRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ResourceStore resourceStore) {
    this(
        config,
        transport,
        capabilityRegistry,
        skillRegistry,
        mcpRegistry,
        journal,
        scheduler,
        taskExecutor,
        resourceStore,
        false);
  }

  private DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonSkillRegistry skillRegistry,
      McpServerRegistry mcpRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ResourceStore resourceStore,
      boolean requireFixedCapabilityCatalog) {
    this.config = Objects.requireNonNull(config, "config");
    this.environmentName = Objects.requireNonNull(config.environmentName(), "environmentName");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    this.mcpRegistry = Objects.requireNonNull(mcpRegistry, "mcpRegistry");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    this.resourceStore = resourceStore;
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    this.environmentInfo =
        new DaemonEnvironmentInfo(
            operatingSystem,
            ZoneId.systemDefault().getId(),
            config.effectiveNote(operatingSystem),
            config.environmentRoot().toString());
    this.directoryBrowser = new EnvironmentDirectoryBrowser(config.environmentRoot());
    this.nextReconnectDelay = config.initialReconnectDelay();
    if (requireFixedCapabilityCatalog
        && !List.copyOf(capabilityRegistry.descriptors())
            .equals(EnvironmentCapabilityCatalog.descriptors())) {
      throw new IllegalStateException(
          "daemon capability registry does not match EnvironmentCapabilityCatalog");
    }
    capabilityRegistry.freeze();
  }

  private static ScheduledThreadPoolExecutor newScheduler() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1, runnable -> new Thread(runnable, "daemon-scheduler"));
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  private static ExecutorService newTaskExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("daemon-task-", 0).factory());
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
    payload.put("protocolVersion", DaemonProtocol.VERSION_4);
    payload.put("gatewayToken", config.gatewayToken());
    payload.put("capabilityCatalogVersion", EnvironmentCapabilityCatalog.version());
    return sendOn(connection, DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private boolean sendReady(ActiveConnection connection) {
    DaemonCapabilities capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            environmentInfo,
            List.copyOf(skillRegistry.descriptors()),
            mcpRegistry.snapshot());
    return sendOn(
        connection, DaemonMessageType.READY, null, capabilitiesCodec.encode(capabilities));
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
      requireProtocolVersion(envelope);
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
        case LIST_DIRECTORY -> {
          requireNoInvocationId(envelope);
          connection.acceptInboundEnvelope(identity);
          processListDirectory(connection, envelope);
        }
        case WELCOME -> {
          connection.acceptInboundEnvelope(identity);
          handleWelcome(connection, envelope);
        }
        case ACK -> {
          connection.acceptInboundEnvelope(identity);
          // Gateway 协议消息不改变 Daemon invocation 事实。
        }
        case ERROR -> {
          connection.acceptInboundEnvelope(identity);
          handleError(connection, envelope);
        }
        default -> throw new DaemonProtocolException(
            "unexpected inbound messageType: " + envelope.messageType());
      }
    } catch (IllegalArgumentException error) {
      sendOn(connection, DaemonMessageType.ERROR, null, errorPayload(error));
    }
  }

  private void requireProtocolVersion(DaemonEnvelope envelope) {
    if (envelope.protocolVersion() != DaemonProtocol.VERSION_4) {
      throw new DaemonProtocolException(
          "daemon runtime requires protocolVersion " + DaemonProtocol.VERSION_4);
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

  /** 处理 gateway ERROR。只有终态名称冲突错误会终止 daemon（停止重连、非零退出）；其余 ERROR 不改变 invocation 事实。 */
  private void handleError(ActiveConnection connection, DaemonEnvelope envelope) {
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    List<String> unexpected = new ArrayList<>();
    payload
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!"message".equals(field) && !"code".equals(field)) {
                unexpected.add(field);
              }
            });
    if (!unexpected.isEmpty()) {
      throw new DaemonProtocolException("ERROR payload has unexpected fields: " + unexpected);
    }
    JsonNode code = payload.get("code");
    if (code == null || code.isNull()) {
      return;
    }
    if (!code.isTextual()
        || !DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT.equals(code.textValue())) {
      throw new DaemonProtocolException("ERROR payload.code is unknown: " + code);
    }
    String message = payload.path("message").asText("");
    failTerminal(
        "environment name is held by another live daemon"
            + (message.isBlank() ? "" : ": " + message));
  }

  private void verifyScope(DaemonEnvelope envelope) {
    if (!environmentName.equals(envelope.environmentName())) {
      throw new DaemonProtocolException(
          "envelope environmentName does not match daemon: " + envelope.environmentName());
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
      // workspace 必须在发送 STARTED / 执行工具之前 canonicalize 为 Environment Root 内现存目录；
      // 形状非法、symlink 越界、路径删除或非目录都是确定性 FAILED，绝不产生 STARTED。
      Path workdir = canonicalWorkspace(payload.workspacePath());
      EnvironmentCapability capability =
          capabilityRegistry
              .find(payload.capabilityId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "unknown Environment capability: " + payload.capabilityId()));
      EnvironmentCapabilityDescriptor descriptor = capability.descriptor();
      if (!descriptor.version().equals(payload.capabilityVersion())) {
        throw new IllegalArgumentException(
            "capabilityVersion does not match environment descriptor: "
                + payload.capabilityVersion());
      }
      Duration timeout = resolveTimeout(payload.timeout(), descriptor);
      EnvironmentCapabilityExecutionRequest request =
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(envelope.invocationId(), payload.argumentsJson()),
              timeout,
              workdir);
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
      EnvironmentCapabilityExecutionHandle handle =
          capability.execute(request, new InvocationListener(invocation));
      invocation.setHandle(Objects.requireNonNull(handle, "capability execution handle"));
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

  private void processListDirectory(ActiveConnection connection, DaemonEnvelope envelope) {
    sendAck(connection, envelope.sequence());
    handleListDirectory(connection, envelope);
  }

  /** control-plane 目录浏览：与 invocation 并行，不进入 journal，不占 active tool slot。以 payload requestId 关联。 */
  private void handleListDirectory(ActiveConnection connection, DaemonEnvelope envelope) {
    DaemonDirectoryCodec.RawListDirectoryRequest raw;
    try {
      raw = directoryCodec.readRequest(envelope.payloadJson());
    } catch (DaemonProtocolException error) {
      sendOn(connection, DaemonMessageType.ERROR, envelope.invocationId(), errorPayload(error));
      return;
    }
    DaemonDirectoryCodec.ListDirectoryRequest request;
    try {
      request = new DaemonDirectoryCodec.ListDirectoryRequest(raw.requestId(), raw.path());
    } catch (IllegalArgumentException error) {
      // 形状非法是确定性业务失败（INVALID_PATH），不是协议 ERROR；requestId 原样回显。
      sendDirectoryListFailed(
          connection,
          envelope,
          new DaemonDirectoryCodec.DirectoryListFailed(
              raw.requestId(),
              raw.path(),
              DaemonDirectoryFailureCode.INVALID_PATH,
              error.getMessage()));
      return;
    }
    try {
      taskExecutor.execute(() -> listDirectoryOnWorker(connection, envelope, request));
    } catch (RejectedExecutionException error) {
      sendDirectoryListFailed(
          connection,
          envelope,
          new DaemonDirectoryCodec.DirectoryListFailed(
              request.requestId(),
              request.path(),
              DaemonDirectoryFailureCode.IO_ERROR,
              "directory listing executor is unavailable"));
    }
  }

  private void listDirectoryOnWorker(
      ActiveConnection connection,
      DaemonEnvelope envelope,
      DaemonDirectoryCodec.ListDirectoryRequest request) {
    try {
      DaemonDirectoryCodec.DirectoryListed listed =
          directoryBrowser.list(request.requestId(), request.path());
      sendOn(
          connection,
          DaemonMessageType.DIRECTORY_LISTED,
          envelope.invocationId(),
          directoryCodec.encodeListed(listed));
    } catch (RuntimeException | IOException error) {
      sendDirectoryListFailed(
          connection, envelope, directoryFailure(request.requestId(), request.path(), error));
    }
  }

  private void sendDirectoryListFailed(
      ActiveConnection connection,
      DaemonEnvelope envelope,
      DaemonDirectoryCodec.DirectoryListFailed failed) {
    sendOn(
        connection,
        DaemonMessageType.DIRECTORY_LIST_FAILED,
        envelope.invocationId(),
        directoryCodec.encodeFailed(failed));
  }

  private static DaemonDirectoryCodec.DirectoryListFailed directoryFailure(
      String requestId, String path, Exception error) {
    DaemonDirectoryFailureCode code;
    if (error instanceof IllegalArgumentException) {
      code = DaemonDirectoryFailureCode.INVALID_PATH;
    } else if (error instanceof NoSuchFileException) {
      code = DaemonDirectoryFailureCode.NOT_FOUND;
    } else if (error instanceof NotDirectoryException) {
      code = DaemonDirectoryFailureCode.NOT_DIRECTORY;
    } else {
      code = DaemonDirectoryFailureCode.IO_ERROR;
    }
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getClass().getSimpleName();
    }
    return new DaemonDirectoryCodec.DirectoryListFailed(requestId, path, code, message);
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
                          "{\"message\":\"capability execution timed out after "
                              + timeout.toMillis()
                              + "ms\"}"),
                      true),
              timeout.toMillis(),
              TimeUnit.MILLISECONDS);
      invocation.setDeadline(deadline);
    } catch (RejectedExecutionException ignored) {
      // close() 已记录取消并停止 scheduler。
    }
  }

  /**
   * 0 timeoutMillis 表示不覆盖 descriptor；descriptor 未设置 deadline 时回退 daemon 默认值，确保每次 invocation 都有有效
   * deadline。
   */
  private Duration resolveTimeout(
      Duration requestedTimeout, EnvironmentCapabilityDescriptor descriptor) {
    if (!requestedTimeout.isZero()) {
      return requestedTimeout;
    }
    return descriptor.timeout().isZero() ? config.defaultToolTimeout() : descriptor.timeout();
  }

  /**
   * INVOKE payload 由 capability codec 严格解码，字段为
   * capabilityId/capabilityVersion/workspacePath/arguments/timeoutMillis；再把 canonical 相对 wire
   * workspace path 解析为 Environment Root 内的 canonical 现存目录。
   *
   * <p>先经共享 workspace validator 做形状校验（{@code '.'} 表示 root、仅 {@code '/'} 分隔、拒绝 absolute/Windows
   * drive/反斜杠/空段/{@code '.'}/{@code '..'} 段/控制字符），再相对 environment root 解析并 {@code toRealPath}：
   * symlink 越界（real path 不在 root 内）、路径删除、非目录都是确定性 {@link IllegalArgumentException}（由调用方收敛为
   * FAILED）；root 内 symlink alias 的 real path 仍在 root 内时允许。
   */
  private Path canonicalWorkspace(String workspacePath) {
    EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath);
    Path candidate = config.environmentRoot().resolve(Path.of(workspacePath)).normalize();
    Path canonical;
    try {
      canonical = candidate.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "workspace does not resolve to an existing directory: " + workspacePath, error);
    }
    if (!canonical.startsWith(config.environmentRoot())) {
      throw new IllegalArgumentException("workspace escapes environment root: " + workspacePath);
    }
    if (!Files.isDirectory(canonical)) {
      throw new IllegalArgumentException("workspace is not a directory: " + workspacePath);
    }
    return canonical;
  }

  private InvokePayload readInvokePayload(DaemonEnvelope envelope) {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        capabilityInvokeCodec.decode(envelope.payloadJson());
    return new InvokePayload(
        request.capabilityId(),
        request.capabilityVersion(),
        request.workspacePath(),
        request.argumentsJson(),
        request.timeout());
  }

  private void requireInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null || envelope.invocationId().isBlank()) {
      throw new DaemonProtocolException(
          envelope.messageType() + " requires a non-blank invocationId");
    }
  }

  private void requireNoInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() != null) {
      throw new DaemonProtocolException(envelope.messageType() + " must not declare invocationId");
    }
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
        DaemonProtocol.VERSION_4,
        messageType,
        environmentName,
        invocationId,
        outboundSequence.getAndIncrement(),
        payloadJson);
  }

  private String errorPayload(Throwable error) {
    return "{\"message\":" + quote(safeFailureMessage(error)) + "}";
  }

  private static String safeFailureMessage(Throwable error) {
    if (error == null) {
      return FALLBACK_FAILURE_MESSAGE;
    }
    try {
      String message = error.getMessage();
      return message == null || message.isBlank() ? FALLBACK_FAILURE_MESSAGE : message;
    } catch (RuntimeException ignored) {
      return FALLBACK_FAILURE_MESSAGE;
    }
  }

  private String quote(String value) {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("value", value);
    return envelopeCodec.writeJson(payload.get("value"));
  }

  @Override
  public void close() {
    shutdown(DaemonRuntimeState.STOPPED, null);
  }

  /** 等待运行时进入终态（显式 {@link #close()} 或终态握手冲突失败）。返回最终 {@link DaemonRuntimeState}，供独立进程入口决定退出码。 */
  public DaemonRuntimeState awaitTermination() throws InterruptedException {
    termination.await();
    return state;
  }

  /** 返回终态失败原因（仅 {@link DaemonRuntimeState#FAILED} 时非 null）。 */
  public String failureReason() {
    return failureReason;
  }

  /**
   * 终态失败：停止重连、终止所有运行中 invocation 并释放终止闩。与 {@link #close()} 共享 shutdown 流程，但以 FAILED 状态结束，
   * 使调用方可以非零退出。
   */
  private void failTerminal(String reason) {
    shutdown(DaemonRuntimeState.FAILED, reason);
  }

  private void shutdown(DaemonRuntimeState terminalState, String reason) {
    ActiveConnection connection;
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      started.set(false);
      state = terminalState;
      failureReason = reason;
      connection = activeConnection.getAndSet(null);
    }
    // shutdown 必须收敛：先停止 transport 接入，再取消任务并停止两个执行资源；单步失败不得跳过后续清理或悬挂 shutdown。
    try {
      if (connection != null) {
        try {
          connection.connection().close();
        } catch (RuntimeException ignored) {
          // 连接关闭失败不影响其余清理。
        }
      }
      closeQuietly(transport);
      running
          .values()
          .forEach(
              invocation -> {
                try {
                  terminal(
                      invocation.invocationId(),
                      new DaemonTerminalMessage(
                          DaemonMessageType.CANCELLED, "{\"reason\":\"daemon stopped\"}"),
                      true);
                } catch (RuntimeException ignored) {
                  // 单个终态通知失败不得阻断其余 invocation 的清理。
                }
              });
      running.clear();
      shutdownExecutors(scheduler, taskExecutor);
      closeQuietly(mcpRegistry);
    } finally {
      termination.countDown();
    }
  }

  private static void shutdownExecutors(ExecutorService scheduler, ExecutorService taskExecutor) {
    shutdownNowQuietly(scheduler);
    shutdownNowQuietly(taskExecutor);
    long deadline = System.nanoTime() + EXECUTOR_TERMINATION_TIMEOUT.toNanos();
    boolean interrupted = awaitTermination(scheduler, deadline);
    interrupted |= awaitTermination(taskExecutor, deadline);
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void shutdownNowQuietly(ExecutorService executor) {
    if (executor == null) {
      return;
    }
    try {
      executor.shutdownNow();
    } catch (RuntimeException ignored) {
      // 继续停止和等待另一个生命周期资源。
    }
  }

  private static boolean awaitTermination(ExecutorService executor, long deadline) {
    if (executor == null) {
      return false;
    }
    boolean interrupted = false;
    while (!executor.isTerminated()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      try {
        if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
          break;
        }
      } catch (InterruptedException ignored) {
        interrupted = true;
      } catch (RuntimeException ignored) {
        break;
      }
    }
    return interrupted;
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // 生命周期清理必须继续收敛。
    }
  }

  @FunctionalInterface
  interface CapabilityRegistrar {

    void register(
        DaemonCapabilityRegistry registry,
        ExecutorService taskExecutor,
        ScheduledExecutorService scheduler);
  }

  private final class InvocationListener implements EnvironmentCapabilityExecutionListener {

    private final RunningInvocation invocation;

    private InvocationListener(RunningInvocation invocation) {
      this.invocation = invocation;
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      if (!isRunning(invocation.invocationId())
          || !matchesInvocation(partial, invocation.invocationId())) {
        return;
      }
      try {
        send(
            DaemonMessageType.PARTIAL,
            invocation.invocationId(),
            resultCodec.encodePartial(toWireToolResult(partial), resourceWriter()));
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "partial");
      }
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      if (!matchesInvocation(result, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"capability result callId does not match invocationId\"}"),
            false);
        return;
      }
      String payload;
      try {
        payload = resultCodec.encodeCompleted(toWireToolResult(result), resourceWriter());
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
    String message = "cannot " + phase + " capability result: " + safeFailureMessage(error);
    terminal(
        invocationId,
        new DaemonTerminalMessage(
            DaemonMessageType.FAILED, errorPayload(new IllegalStateException(message, error))),
        false);
  }

  /** 仅在 Daemon wire codec 边界将 capability result 映射为现有 wire codec 所需的等价结果。 */
  private ToolResult toWireToolResult(EnvironmentCapabilityResult result) {
    return new ToolResult(result.callId(), result.contents(), result.error(), result.detailsJson());
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

  private boolean matchesInvocation(EnvironmentCapabilityResult result, String invocationId) {
    return result != null && invocationId.equals(result.callId());
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
    private final AtomicReference<EnvironmentCapabilityExecutionHandle> handle =
        new AtomicReference<>();
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

    private void setHandle(EnvironmentCapabilityExecutionHandle value) {
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
        EnvironmentCapabilityExecutionHandle value = handle.get();
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
      EnvironmentName environmentName,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentName(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }

  private record InvokePayload(
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String workspacePath,
      String argumentsJson,
      Duration timeout) {}
}
