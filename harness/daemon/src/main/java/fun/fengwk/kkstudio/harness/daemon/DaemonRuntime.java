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
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillLoadCapability;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.daemon.transport.JdkWebSocketTransport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceStore;

import java.io.IOException;
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
 * <p>Invocation journal 是进程内执行事实源；WebSocket 连接仅作为消息传输管道。连接断开后 Daemon 保留 journal 事实并重新握手与重连； 若
 * Gateway 再次发送相同 {@code invocationId}，RUNNING 与 terminal journal 条目分别重放 STARTED 与 terminal 报文。
 *
 * <p>每次连接尝试由独立的 {@code connectionGeneration} 标识，隔离跨连接事件干扰；入站消息在单个代际内执行严格单调序列检查。
 *
 * <p>Daemon 只拥有两个执行生命周期资源：单线程 scheduler 处理 heartbeat、reconnect 与 timeout，共享的
 * virtual-thread-per-task executor 处理 Coding/目录浏览等阻塞调用。transport/JDK 内部线程不在该生命周期内。
 */
public final class DaemonRuntime implements AutoCloseable {

  private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
  private static final String FALLBACK_FAILURE_MESSAGE = "capability execution failed";

  private final DaemonConfig config;
  private volatile EnvironmentId boundEnvironmentId;
  private final DaemonTransport transport;
  private final DaemonCapabilityRegistry capabilityRegistry;
  private final DaemonSkillRegistry skillRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService taskExecutor;
  private final ResourceStore resourceStore;
  private final DaemonEnvironmentInfo environmentInfo;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
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
   * 独占生命周期；任一步构造失败都会释放 transport 和已创建的执行资源。
   */
  public static DaemonRuntime create(
      DaemonConfig config, CodingToolsConfig toolsConfig, DaemonSkillRegistry skillRegistry) {
    Objects.requireNonNull(toolsConfig, "toolsConfig");
    return create(
        config,
        skillRegistry,
        toolsConfig.resourceStore(),
        (registry, executor, scheduler) -> {
          CodingCapabilities.registerAll(registry, toolsConfig, executor, scheduler);
          registry.register(new SkillLoadCapability(skillRegistry, executor));
        });
  }

  static DaemonRuntime create(
      DaemonConfig config,
      DaemonSkillRegistry skillRegistry,
      ResourceStore resourceStore,
      CapabilityRegistrar registrar) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(skillRegistry, "skillRegistry");
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
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ResourceStore resourceStore,
      boolean requireFixedCapabilityCatalog) {
    this.config = Objects.requireNonNull(config, "config");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
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

  /** 阻塞等待 daemon 终止（由 close 或终态冲突触发）；返回终态。 */
  public DaemonRuntimeState awaitTermination() throws InterruptedException {
    termination.await();
    return state;
  }

  /** 返回当前生命周期状态。 */
  public DaemonRuntimeState state() {
    return state;
  }

  /** 返回终态失败原因；非 FAILED 时为 {@code null}。 */
  public String failureReason() {
    return failureReason;
  }

  public EnvironmentId boundEnvironmentId() {
    return boundEnvironmentId;
  }

  DaemonInvocationJournal journal() {
    return journal;
  }

  DaemonEnvironmentInfo environmentInfo() {
    return environmentInfo;
  }

  ActiveConnection activeConnection() {
    return activeConnection.get();
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
    // 每次尝试连接递增代际，使旧连接的延迟回调与已过时代际事件失效。
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
                // 若运行时已关闭或代际已过时，安全释放新连接。
                if (!started.get() || generation != connectionGeneration.get()) {
                  try {
                    connection.close();
                  } catch (RuntimeException ignored) {
                  }
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

  private void handleDisconnected(long generation) {
    ActiveConnection connection = activeConnection.get();
    // 仅当断开事件与当前活跃连接代际一致时执行清理。
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
    // 连接失效不影响 Invocation journal；重置绑定并按指数退避安排重连。
    this.boundEnvironmentId = null;
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
    payload.put("protocolVersion", DaemonProtocol.VERSION);
    payload.put("registrationToken", config.registrationToken());
    payload.put("capabilityCatalogVersion", EnvironmentCapabilityCatalog.version());
    return sendOn(connection, DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private boolean sendReady(ActiveConnection connection) {
    DaemonCapabilities capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION, environmentInfo, List.copyOf(skillRegistry.descriptors()));
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
    if (envelope.protocolVersion() != DaemonProtocol.VERSION) {
      throw new DaemonProtocolException(
          "daemon runtime requires protocolVersion " + DaemonProtocol.VERSION);
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
      this.boundEnvironmentId =
          Objects.requireNonNull(envelope.environmentId(), "WELCOME environmentId");
      if (sendReady(connection) && activeConnection.get() == connection) {
        connection.markReady();
        state = DaemonRuntimeState.READY;
      }
    }
  }

  /**
   * 处理 Gateway ERROR。注册凭证被拒（REGISTRATION_REJECTED）是终态的；RETRY_LATER 触发退避重连；其余 ERROR 不改变 Invocation
   * journal 事实。
   */
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
    if (!code.isTextual()) {
      throw new DaemonProtocolException("ERROR payload.code is unknown: " + code);
    }
    String codeValue = code.textValue();
    if (DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED.equals(codeValue)) {
      String message = payload.path("message").asText("");
      failTerminal(
          "environment registration is rejected" + (message.isBlank() ? "" : ": " + message));
      return;
    }
    if (DaemonProtocol.ERROR_CODE_RETRY_LATER.equals(codeValue)) {
      handleDisconnected(connection.generation());
      return;
    }
    throw new DaemonProtocolException("ERROR payload.code is unknown: " + code);
  }

  private void verifyScope(DaemonEnvelope envelope) {
    if (envelope.messageType() == DaemonMessageType.HELLO) {
      if (envelope.environmentId() != null) {
        throw new DaemonProtocolException("HELLO must not declare environmentId");
      }
      return;
    }
    if (envelope.messageType() == DaemonMessageType.WELCOME) {
      if (envelope.environmentId() == null) {
        throw new DaemonProtocolException("WELCOME must declare non-null environmentId");
      }
      return;
    }
    if (boundEnvironmentId != null && envelope.environmentId() != null) {
      if (!boundEnvironmentId.equals(envelope.environmentId())) {
        throw new DaemonProtocolException(
            "envelope environmentId does not match daemon: " + envelope.environmentId());
      }
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
    // 原子去重：若 journal 已存在该 invocationId，重放既有状态（RUNNING 重放 STARTED(replayed=true)，终态重放对应 terminal
    // 报文）。
    DaemonInvocationJournalStart start = journal.start(envelope.invocationId());
    if (!start.created()) {
      replay(start.entry(), envelope.invocationId());
      return;
    }

    try {
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
      // 仅当调用参数、能力元数据与运行时预检全部通过后才发出 STARTED。
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

  /**
   * 原子终态仲裁与结果派发。
   *
   * <p>存在 {@link RunningInvocation} 时，正常完成、执行失败、调度器超时、主动取消与停机先通过 {@link
   * RunningInvocation#claimTerminal(boolean)} 仲裁本地执行资源；预检失败等尚未建立本地执行的路径直接进入 journal。两类路径最终都以 {@link
   * DaemonInvocationJournal#complete(String, DaemonTerminalMessage)} 的 RUNNING 到终态原子跃迁作为唯一提交点；
   * 只有提交成功者发送终态报文。
   */
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
    } catch (RuntimeException error) {
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(
              DaemonMessageType.FAILED,
              "{\"message\":\"cannot schedule capability timeout: " + error.getMessage() + "\"}"),
          true);
    }
  }

  private boolean isRunning(String invocationId) {
    return journal
        .find(invocationId)
        .map(entry -> entry.state() == DaemonInvocationState.RUNNING)
        .orElse(false);
  }

  private void sendAck(ActiveConnection connection, long acknowledgedSequence) {
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put("acknowledgedSequence", acknowledgedSequence);
    sendOn(connection, DaemonMessageType.ACK, null, envelopeCodec.writeJson(payload));
  }

  private boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
    ActiveConnection connection = activeConnection.get();
    return connection != null && sendOn(connection, messageType, invocationId, payloadJson);
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

  /**
   * Daemon 出站 envelope：HELLO 在认证前没有 Environment scope（必须为 null）；其余消息都由已绑定连接发出（携带本 daemon 的 {@link
   * EnvironmentId}）。
   */
  private DaemonEnvelope envelope(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    boolean hello = messageType == DaemonMessageType.HELLO;
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        messageType,
        hello ? null : boundEnvironmentId,
        invocationId,
        outboundSequence.getAndIncrement(),
        payloadJson);
  }

  private String errorPayload(Throwable error) {
    return "{\"message\":" + quote(safeFailureMessage(error)) + "}";
  }

  /** 按 requested -> descriptor -> daemon default 三层规则解析有效执行超时。 */
  private Duration resolveTimeout(
      Duration requestedTimeout, EnvironmentCapabilityDescriptor descriptor) {
    Objects.requireNonNull(requestedTimeout, "requestedTimeout");
    Objects.requireNonNull(descriptor, "descriptor");
    if (!requestedTimeout.isZero()) {
      return requestedTimeout;
    }
    return descriptor.timeout().isZero() ? config.defaultToolTimeout() : descriptor.timeout();
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

  private InvokePayload readInvokePayload(DaemonEnvelope envelope) {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        capabilityInvokeCodec.decode(envelope.payloadJson());
    return new InvokePayload(
        request.capabilityId(),
        request.capabilityVersion(),
        request.argumentsJson(),
        request.timeout());
  }

  private void requireInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null) {
      throw new DaemonProtocolException(envelope.messageType() + " requires non-null invocationId");
    }
  }

  /** 终态失败：停止重连、标记 FAILED 并释放资源。 */
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
                invocation.cancel();
                journal.complete(
                    invocation.invocationId(),
                    new DaemonTerminalMessage(
                        DaemonMessageType.CANCELLED, "{\"reason\":\"daemon shutdown\"}"));
              });
      running.clear();
      shutdownExecutors(scheduler, taskExecutor);
    } finally {
      termination.countDown();
    }
  }

  @Override
  public void close() {
    shutdown(DaemonRuntimeState.STOPPED, null);
  }

  private static void shutdownExecutors(
      ScheduledExecutorService scheduler, ExecutorService taskExecutor) {
    if (taskExecutor != null) {
      taskExecutor.shutdownNow();
      try {
        taskExecutor.awaitTermination(
            EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
      try {
        scheduler.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }

  @FunctionalInterface
  interface CapabilityRegistrar {
    void register(
        DaemonCapabilityRegistry registry,
        ExecutorService taskExecutor,
        ScheduledExecutorService scheduler);
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

    long generation() {
      return generation;
    }

    DaemonConnection connection() {
      return connection;
    }

    /**
     * 在当前连接代际内校验并记录入站 envelope sequence。
     *
     * <p>严格约束：
     *
     * <ul>
     *   <li>序列号必须从基线开始严格相邻单调递增（sequence == last + 1）；
     *   <li>相同序列号且 envelope 完全相同判定为网络重放（duplicate），静默接受；
     *   <li>相同序列号但内容不一致判定为序号冲突复用，抛出协议异常；
     *   <li>序列号回退或出现空洞跳号均判定为协议违规，拒绝处理。
     * </ul>
     */
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

    void markHelloSent() {
      helloSent.set(true);
    }

    boolean helloSent() {
      return helloSent.get();
    }

    boolean markWelcomed() {
      return welcomed.compareAndSet(false, true);
    }

    void markReady() {
      ready.set(true);
    }
  }

  private record InboundEnvelopeIdentity(
      int protocolVersion,
      DaemonMessageType messageType,
      EnvironmentId environmentId,
      String invocationId,
      long sequence,
      JsonNode payload) {

    private static InboundEnvelopeIdentity from(DaemonEnvelope envelope, JsonNode payload) {
      return new InboundEnvelopeIdentity(
          envelope.protocolVersion(),
          envelope.messageType(),
          envelope.environmentId(),
          envelope.invocationId(),
          envelope.sequence(),
          payload);
    }
  }

  private record InvokePayload(
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String argumentsJson,
      Duration timeout) {}

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

    private boolean isTerminal() {
      return terminal.get();
    }

    /**
     * 尝试原子抢占终态仲裁权（terminal-once 互斥点）。
     *
     * @param cancelHandle 是否在抢占成功后同时取消底层执行句柄
     * @return 若当前线程首次将 terminal 标记置为 true 返回 true，否则返回 false（表明已有其它终态路径抢占）
     */
    private boolean claimTerminal(boolean cancelHandle) {
      if (!terminal.compareAndSet(false, true)) {
        return false;
      }
      cancelDeadline();
      if (cancelHandle) {
        cancel();
      }
      return true;
    }

    private boolean begin() {
      return begun.compareAndSet(false, true);
    }

    private void setHandle(EnvironmentCapabilityExecutionHandle handle) {
      this.handle.set(handle);
      if (cancelled.get()) {
        handle.cancel();
      }
    }

    private void setDeadline(ScheduledFuture<?> deadline) {
      this.deadline.set(deadline);
    }

    private void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        EnvironmentCapabilityExecutionHandle current = handle.get();
        if (current != null) {
          current.cancel();
        }
      }
    }

    private void cancelDeadline() {
      ScheduledFuture<?> current = deadline.getAndSet(null);
      if (current != null) {
        current.cancel(false);
      }
    }
  }

  private void failResultEncoding(String invocationId, RuntimeException error, String phase) {
    String message = "cannot " + phase + " capability result: " + safeFailureMessage(error);
    terminal(
        invocationId,
        new DaemonTerminalMessage(
            DaemonMessageType.FAILED, errorPayload(new IllegalStateException(message, error))),
        false);
  }

  private DaemonResourceStore resourceWriter() {
    ResourceStore store = this.resourceStore;
    return new DaemonResourceStore() {
      @Override
      public DaemonResourceRef store(byte[] bytes, String mediaType) throws IOException {
        if (store == null) {
          throw new IllegalStateException("resource store is not configured");
        }
        return store.store(bytes, mediaType);
      }

      @Override
      public byte[] read(DaemonResourceRef ref) throws IOException {
        if (store == null) {
          throw new IllegalStateException("resource store is not configured");
        }
        return store.read(ref);
      }
    };
  }

  private final class InvocationListener implements EnvironmentCapabilityExecutionListener {
    private final RunningInvocation invocation;

    private InvocationListener(RunningInvocation invocation) {
      this.invocation = invocation;
    }

    private static boolean matchesInvocation(
        EnvironmentCapabilityResult result, String invocationId) {
      return result != null && invocationId.equals(result.callId());
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      if (invocation.isTerminal() || !isRunning(invocation.invocationId())) {
        return;
      }
      if (!matchesInvocation(partial, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"capability partial callId does not match invocationId\"}"),
            true);
        return;
      }
      try {
        String payloadJson = resultCodec.encodePartial(partial, resourceWriter());
        send(DaemonMessageType.PARTIAL, invocation.invocationId(), payloadJson);
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
      try {
        String payloadJson = resultCodec.encodeCompleted(result, resourceWriter());
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(DaemonMessageType.COMPLETED, payloadJson),
            false);
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "complete");
      }
    }

    @Override
    public void onError(Throwable error) {
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error)),
          false);
    }
  }
}
