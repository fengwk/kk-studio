package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingCapabilities;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.LspService;
import fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalStart;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillPackageInstaller;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.daemon.transport.OkHttpWebSocketTransport;
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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommandCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResultCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Environment Daemon 的连接、协议和本地 Environment Capability 执行基座。
 *
 * <p>Invocation journal 是进程内执行事实源；WebSocket 连接仅作为消息传输管道。连接断开后 Daemon 保留 journal 事实并重新握手与重连； 若
 * Gateway 再次发送相同 {@code invocationId}（包括物理连接丢失后的同实例重连重放），RUNNING 与 terminal journal 条目分别重放 STARTED
 * 与 terminal 报文，绝不重复执行副作用。
 *
 * <p>本进程的 {@code daemonInstanceId} 在构造期随机生成一次并在每个 HELLO 中声明；同一进程的多次重连复用同一身份，因此 Gateway 可以区分「同一
 * Daemon 重连」与「另一个 Daemon 进程接管」。
 *
 * <p>每次连接尝试由独立的 {@code connectionGeneration} 标识，隔离跨连接事件干扰；wire 协议没有序号，入站消息不做跨消息顺序校验。
 *
 * <p>Daemon 只拥有两个执行生命周期资源：单线程 scheduler 处理 heartbeat、reconnect 与 timeout，共享的
 * virtual-thread-per-task executor 处理 Coding/目录浏览等阻塞调用与 Git 网络取消观察。transport/JDK 内部线程不在该生命周期内。
 */
public final class DaemonRuntime implements AutoCloseable {

  private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
  private static final String FALLBACK_FAILURE_MESSAGE = "capability execution failed";

  /** 进程内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
  private static final int MAX_TRACKED_UPDATE_OPERATIONS = 8;

  private final DaemonConfig config;

  /** 本 Daemon 在 WELCOME 中收到的 Environment 绑定；WELCOME 之前为 null，断开时重置。 */
  private final AtomicReference<EnvironmentId> boundEnvironmentId;

  private final DaemonTransport transport;
  private final DaemonCapabilityRegistry capabilityRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService taskExecutor;
  private final ExecutorService lspExecutor;

  /** capability 持有的进程内资源（当前是 LSP 客户端池）；由本运行时负责在 shutdown 时关闭。 */
  private final AutoCloseable capabilityResources;

  /** 受控临时 workspace 存储；由 coding capabilities 共享，用于按最新策略清扫过期 workspace。测试构造下可为 {@code null}。 */
  private final TextOutputStore textOutputStore;

  private final DaemonEnvironmentInfo environmentInfo;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonResourceTransferClient resourceTransferClient;
  private final OkHttpClient resourceHttpClient;

  /** 本连接 WELCOME 通告的单条/聚合资源字节预算；用于本地预检，避免注定被服务端拒绝的上传。 */
  private final AtomicLong maxResourceBytes = new AtomicLong();

  /** 最近一次 WELCOME 通告的临时资源保留期（秒）；0 表示尚未收到策略。 */
  private volatile long temporaryResourceTtlSeconds;

  /** 最近一次 WELCOME 通告的临时资源扫描间隔（秒）；用于按最新策略重排扫描任务。 */
  private volatile long temporaryResourceCleanupIntervalSeconds;

  private final Object temporaryResourcePolicyLock = new Object();

  /** 当前已排定的清扫间隔（秒）；仅由 {@link #temporaryResourcePolicyLock} 保护，用于避免重复调度。 */
  private long scheduledCleanupIntervalSeconds;

  private ScheduledFuture<?> temporaryResourceSweep;

  /** 本 Daemon 进程的生命周期身份：构造期随机生成一次，所有重连复用，用于区分同实例恢复与换进程接管。 */
  private final String daemonInstanceId = UUID.randomUUID().toString();

  /** 本 Daemon 的构建版本：HELLO/READY 上报的事实，未打包时为 {@code development}。 */
  private final String daemonVersion = DaemonBuildInfo.version();

  private final DaemonUpdateCommandCodec updateCommandCodec = new DaemonUpdateCommandCodec();
  private final DaemonUpdateResultCodec updateResultCodec = new DaemonUpdateResultCodec();

  /**
   * 受管更新操作的结果缓存（operationId -> 冻结回执）：同一 operation 的重发只重放结果，不产生第二次下载。
   *
   * <p>保留最近若干条并在重连后重发，使断开不等于失败时 Platform 能重新收敛。
   */
  private final Map<String, DaemonUpdateResult> updateResults =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DaemonUpdateResult> eldest) {
              return size() > MAX_TRACKED_UPDATE_OPERATIONS;
            }
          });

  /** 正在准备的 operationId；null 表示当前没有更新在途。 */
  private final AtomicReference<String> activeUpdateOperationId = new AtomicReference<>();

  private volatile ManagedUpdatePreparer managedUpdater;
  private volatile ManagedUpdateLauncher updateLauncher;

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
   * 创建完整生产运行时。LSP 服务、scheduler 与 virtual-thread-per-task executor 在注册 capability 前创建并注入，成功后由
   * runtime 独占生命周期；任一步构造失败都会释放 transport、LSP 客户端和已创建的执行资源。
   */
  public static DaemonRuntime create(
      DaemonConfig config, CodingToolsConfig toolsConfig, DaemonDataDirectory dataDirectory) {
    Objects.requireNonNull(toolsConfig, "toolsConfig");
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    return create(
        config,
        toolsConfig.textOutputStore(),
        (registry, executor, scheduler, lspExecutor) -> {
          // 先打开 Git 安装器（无持有资源），再创建 LSP：任一环节失败都不会留下需要 LSP 收尾的半成品。
          SkillPackageInstaller skillInstaller =
              SkillPackageInstaller.open(dataDirectory, executor);
          LspService lspService = LspService.create(toolsConfig.lsp(), lspExecutor, scheduler);
          CodingCapabilities.registerAll(
              registry, toolsConfig, lspService, skillInstaller, executor, scheduler);
          return lspService;
        });
  }

  static DaemonRuntime create(
      DaemonConfig config, TextOutputStore textOutputStore, CapabilityRegistrar registrar) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(registrar, "registrar");
    ScheduledThreadPoolExecutor scheduler = newScheduler();
    ExecutorService taskExecutor = null;
    ExecutorService lspExecutor = null;
    DaemonTransport transport = null;
    AutoCloseable capabilityResources = null;
    boolean completed = false;
    try {
      taskExecutor = newTaskExecutor();
      lspExecutor = newLspExecutor();
      transport = new OkHttpWebSocketTransport(config.gatewayUri());
      DaemonCapabilityRegistry capabilityRegistry = new DaemonCapabilityRegistry();
      capabilityResources =
          registrar.register(capabilityRegistry, taskExecutor, scheduler, lspExecutor);
      DaemonRuntime runtime =
          new DaemonRuntime(
              config,
              transport,
              capabilityRegistry,
              new InMemoryDaemonInvocationJournal(),
              scheduler,
              taskExecutor,
              lspExecutor,
              capabilityResources,
              textOutputStore,
              true);
      completed = true;
      return runtime;
    } finally {
      if (!completed) {
        closeQuietly(capabilityResources);
        closeQuietly(transport);
        shutdownExecutors(scheduler, taskExecutor, lspExecutor);
      }
    }
  }

  /** 使用可替换 transport、journal 与执行资源创建运行时；注入资源的生命周期移交给 runtime。 */
  DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor) {
    this(
        config,
        transport,
        capabilityRegistry,
        journal,
        scheduler,
        taskExecutor,
        null,
        null,
        null,
        false);
  }

  private DaemonRuntime(
      DaemonConfig config,
      DaemonTransport transport,
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ExecutorService lspExecutor,
      AutoCloseable capabilityResources,
      TextOutputStore textOutputStore,
      boolean requireFixedCapabilityCatalog) {
    this.config = Objects.requireNonNull(config, "config");
    this.boundEnvironmentId = new AtomicReference<>();
    this.transport = Objects.requireNonNull(transport, "transport");
    this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    this.lspExecutor = lspExecutor;
    this.capabilityResources = capabilityResources;
    this.textOutputStore = textOutputStore;
    this.resourceHttpClient =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    this.resourceTransferClient =
        new DaemonResourceTransferClient(resourceHttpClient, this::sendTransferControl);
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    this.environmentInfo =
        detectEnvironmentInfo(
            config, operatingSystem, controlledTemporaryDirectory(textOutputStore, config));
    this.nextReconnectDelay = config.initialReconnectDelay();
    if (requireFixedCapabilityCatalog
        && !List.copyOf(capabilityRegistry.descriptors()).equals(fixedCapabilityDescriptors())) {
      throw new IllegalStateException(
          "daemon capability registry does not match EnvironmentCapabilityCatalog");
    }
    capabilityRegistry.freeze();
  }

  /** 生产装配注册的 capability descriptor 全集。 */
  private static List<EnvironmentCapabilityDescriptor> fixedCapabilityDescriptors() {
    return List.copyOf(EnvironmentCapabilityCatalog.descriptors());
  }

  /**
   * 采集真实进程宿主事实：进程用户、canonical HOME 与受控临时目录。
   *
   * <p>这些只是模型可见的展示事实，不构成 cwd、默认 workdir 或沙箱；HOME 无法 canonical 化时退化为绝对规范化路径，宿主目录缺失不阻止 Daemon 启动。
   */
  private static DaemonEnvironmentInfo detectEnvironmentInfo(
      DaemonConfig config, DaemonOperatingSystem operatingSystem, String tempDirectory) {
    return new DaemonEnvironmentInfo(
        operatingSystem,
        ZoneId.systemDefault().getId(),
        requireHostProperty("user.name"),
        canonicalHomeDirectory(requireHostProperty("user.home")),
        config.effectiveNote(operatingSystem),
        tempDirectory);
  }

  /**
   * 上报的受控临时目录：优先取受控 workspace 根的父目录（{@code <canonical-data-root>/tmp}，已是 canonical 绝对路径）；
   * 无受控存储（测试构造）时退化为配置数据目录下的 {@code tmp}。
   */
  private static String controlledTemporaryDirectory(
      TextOutputStore textOutputStore, DaemonConfig config) {
    if (textOutputStore != null) {
      Path tmp = textOutputStore.root().getParent();
      if (tmp != null) {
        return tmp.toString();
      }
    }
    return config.dataDir().toAbsolutePath().normalize().resolve("tmp").toString();
  }

  private static String requireHostProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("system property " + name + " must be present");
    }
    return value;
  }

  private static String canonicalHomeDirectory(String home) {
    Path path = Path.of(home);
    try {
      return path.toRealPath().toString();
    } catch (IOException error) {
      return path.toAbsolutePath().normalize().toString();
    }
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

  /**
   * LSP 客户端 stdio 的阻塞 I/O 执行器。
   *
   * <p>进程管道读取会占用虚拟线程的调度载体，因此这里刻意使用平台线程；线程数跟随 LSP 客户端数量，空闲线程自行回收。
   */
  private static ExecutorService newLspExecutor() {
    AtomicInteger counter = new AtomicInteger();
    return Executors.newCachedThreadPool(
        runnable -> {
          Thread thread = new Thread(runnable, "daemon-lsp-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        });
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
    return boundEnvironmentId.get();
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
                  } else {
                    // HELLO 未进入传输：停留在 CONNECTING 会挡住后续重连，必须关闭这条连接。
                    closeFailedHandshake(active);
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
    // 连接失效不影响 Invocation journal；重置绑定与资源字节预算并清空等待中的上传票据。
    resourceTransferClient.onConnectionLost();
    maxResourceBytes.set(0L);
    this.boundEnvironmentId.set(null);
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
    payload.put("daemonVersion", daemonVersion);
    payload.put("daemonInstanceId", daemonInstanceId);
    return sendOn(connection, DaemonMessageType.HELLO, null, envelopeCodec.writeJson(payload));
  }

  private boolean sendReady(ActiveConnection connection) {
    DaemonCapabilities capabilities =
        new DaemonCapabilities(DaemonCapabilities.VERSION, daemonVersion, environmentInfo);
    return sendOn(
        connection, DaemonMessageType.READY, null, capabilitiesCodec.encode(capabilities));
  }

  private void sendHeartbeat() {
    if (state == DaemonRuntimeState.READY) {
      send(DaemonMessageType.HEARTBEAT, null, "{}");
    }
  }

  /**
   * 处理服务端下发的受管更新命令：只在 READY 绑定连接上接受；同一 operation 幂等。
   *
   * <p>命令先在接收路径上立即回执 {@code ACCEPTED}（让 Platform 尽快进入 RUNNING），下载/校验/预检在独立任务线程执行并以 {@code
   * PREPARED}/{@code FAILED} 回执。任何失败都保留旧二进制不动。
   */
  private void handleUpdate(DaemonEnvelope envelope) {
    if (state != DaemonRuntimeState.READY || envelope.environmentId() == null) {
      throw new DaemonProtocolException("UPDATE requires a READY bound connection");
    }
    DaemonUpdateCommand command = updateCommandCodec.decode(envelope.payloadJson());
    DaemonUpdateResult completed = updateResults.get(command.operationId());
    if (completed != null) {
      sendUpdateResult(completed);
      return;
    }
    if (!activeUpdateOperationId.compareAndSet(null, command.operationId())) {
      if (command.operationId().equals(activeUpdateOperationId.get())) {
        return;
      }
      throw new DaemonProtocolException("a different update operation is already in progress");
    }
    sendUpdateResult(DaemonUpdateResult.accepted(command.operationId()));
    try {
      taskExecutor.execute(() -> runUpdate(command));
    } catch (RejectedExecutionException error) {
      activeUpdateOperationId.compareAndSet(command.operationId(), null);
      sendUpdateResult(
          DaemonUpdateResult.failed(command.operationId(), "update executor rejected"));
    }
  }

  private void runUpdate(DaemonUpdateCommand command) {
    DaemonUpdateResult result;
    try {
      result =
          switch (managedUpdater().prepare(command)) {
            case ManagedUpdateOutcome.Prepared prepared -> updateLauncher().launch(prepared)
                ? DaemonUpdateResult.prepared(command.operationId())
                : DaemonUpdateResult.failed(
                    command.operationId(), "detached updater could not be started");
            case ManagedUpdateOutcome.Failed failed -> DaemonUpdateResult.failed(
                command.operationId(), failed.message());
          };
    } catch (RuntimeException error) {
      result = DaemonUpdateResult.failed(command.operationId(), "update preparation failed");
    } finally {
      activeUpdateOperationId.compareAndSet(command.operationId(), null);
    }
    updateResults.put(command.operationId(), result);
    sendUpdateResult(result);
  }

  private void sendUpdateResult(DaemonUpdateResult result) {
    if (state == DaemonRuntimeState.READY) {
      send(DaemonMessageType.UPDATE_RESULT, null, updateResultCodec.encode(result));
    }
  }

  /** 重连 READY 后重发已知更新回执：进行中重发 ACCEPTED，否则重发最近一次已完成回执。 */
  private void resendUpdateResultAfterReconnect() {
    String active = activeUpdateOperationId.get();
    if (active != null && !updateResults.containsKey(active)) {
      sendUpdateResult(DaemonUpdateResult.accepted(active));
      return;
    }
    List<DaemonUpdateResult> snapshot;
    synchronized (updateResults) {
      snapshot = new ArrayList<>(updateResults.values());
    }
    if (!snapshot.isEmpty()) {
      sendUpdateResult(snapshot.get(snapshot.size() - 1));
    }
  }

  private ManagedUpdatePreparer managedUpdater() {
    ManagedUpdatePreparer current = managedUpdater;
    if (current == null) {
      synchronized (this) {
        current = managedUpdater;
        if (current == null) {
          current = new ManagedDaemonUpdater(config.dataDir());
          managedUpdater = current;
        }
      }
    }
    return current;
  }

  private ManagedUpdateLauncher updateLauncher() {
    ManagedUpdateLauncher current = updateLauncher;
    if (current == null) {
      synchronized (this) {
        current = updateLauncher;
        if (current == null) {
          current = new DetachedUpdateLauncher(environmentInfo.operatingSystem());
          updateLauncher = current;
        }
      }
    }
    return current;
  }

  /** 测试注入点：替换分离更新器启动器，避免真实 fork 进程。 */
  void setUpdateLauncher(ManagedUpdateLauncher launcher) {
    this.updateLauncher = launcher;
  }

  /** 测试注入点：替换更新准备器，避免真实网络下载。 */
  void setManagedUpdater(ManagedUpdatePreparer updater) {
    this.managedUpdater = updater;
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
      switch (envelope.messageType()) {
        case INVOKE -> {
          requireInvocationId(envelope);
          handleInvoke(connection, envelope, readInvokePayload(envelope));
        }
        case CANCEL -> {
          requireInvocationId(envelope);
          handleCancel(envelope);
        }
        case WELCOME -> handleWelcome(connection, envelope);
        case TEMPORARY_RESOURCE_POLICY -> handleTemporaryResourcePolicy(envelope);
        case ERROR -> handleError(connection, envelope);
        case UPDATE -> handleUpdate(envelope);
          // 上传票据是调用作用域的控制平面响应，绝不进入通用协议处理。
        case RESOURCE_UPLOAD_TICKET -> requireInvocationIdAndDeliverTicket(envelope);
        case READY,
            HEARTBEAT,
            STARTED,
            PROGRESS,
            COMPLETED,
            FAILED,
            CANCELLED,
            RESOURCE_UPLOAD_REQUEST,
            RESOURCE_UPLOAD_COMMIT,
            UPDATE_RESULT -> throw new DaemonProtocolException(
            "daemon must not receive " + envelope.messageType() + " from server");
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
      this.boundEnvironmentId.set(
          Objects.requireNonNull(envelope.environmentId(), "WELCOME environmentId"));
      ObjectNode payload = envelopeCodec.readPayload(envelope);
      // 资源字节预算与临时资源策略由服务端在 WELCOME 中通告；缺失或非正数都 fail closed。
      maxResourceBytes.set(requiredPositiveLong(payload, "WELCOME", "maxResourceBytes"));
      long ttlSeconds = requiredPositiveLong(payload, "WELCOME", "temporaryResourceTtlSeconds");
      long cleanupIntervalSeconds =
          requiredPositiveLong(payload, "WELCOME", "temporaryResourceCleanupIntervalSeconds");
      applyTemporaryResourcePolicy(ttlSeconds, cleanupIntervalSeconds);
      if (!sendReady(connection)) {
        // WELCOME 已接受但 READY 未进入传输：不得停在 CONNECTING，关闭后由既有重连恢复。
        if (activeConnection.get() == connection) {
          closeFailedHandshake(connection);
        }
        return;
      }
      if (activeConnection.get() == connection) {
        connection.markReady();
        state = DaemonRuntimeState.READY;
        // 只有 READY 连接才能递交上传控制帧；放行在断连期间等待重连的上传继续重放同一 transfer。
        resourceTransferClient.onConnectionReady();
        // 断开不改变已接受的更新事实：重连后重发已知回执，让 Platform 重新收敛而不是判定失败。
        resendUpdateResultAfterReconnect();
      }
    }
  }

  /** 握手帧未成功递交时关闭当前连接，让既有断开路径调度重连；不额外轮询。 */
  private void closeFailedHandshake(ActiveConnection connection) {
    try {
      connection.connection().close();
    } catch (RuntimeException ignored) {
      // 关闭失败仍要走代际断开，避免停在 CONNECTING。
    }
    handleDisconnected(connection.generation());
  }

  /** 严格读取一个正 long 字段：缺失、非整数或非正都是协议错误。 */
  private static long requiredPositiveLong(ObjectNode payload, String frame, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new DaemonProtocolException(frame + " must declare integer '" + field + "'");
    }
    long parsed = value.longValue();
    if (parsed <= 0) {
      throw new DaemonProtocolException(frame + " '" + field + "' must be positive");
    }
    return parsed;
  }

  /**
   * 应用服务端在同一控制通道上推送的临时资源策略热更新；只在 WELCOME 建立绑定后接受。
   *
   * <p>已 READY 的连接不会再次收到 WELCOME，因此设置热更经 {@code TEMPORARY_RESOURCE_POLICY} 帧到达，幂等地重排清扫任务。
   */
  private void handleTemporaryResourcePolicy(DaemonEnvelope envelope) {
    if (state != DaemonRuntimeState.READY || envelope.environmentId() == null) {
      throw new DaemonProtocolException(
          "TEMPORARY_RESOURCE_POLICY requires a READY bound connection");
    }
    ObjectNode payload = envelopeCodec.readPayload(envelope);
    long ttlSeconds =
        requiredPositiveLong(payload, "TEMPORARY_RESOURCE_POLICY", "temporaryResourceTtlSeconds");
    long cleanupIntervalSeconds =
        requiredPositiveLong(
            payload, "TEMPORARY_RESOURCE_POLICY", "temporaryResourceCleanupIntervalSeconds");
    applyTemporaryResourcePolicy(ttlSeconds, cleanupIntervalSeconds);
  }

  /**
   * 应用最新临时资源策略：记录保留期并按最新扫描间隔（重）排定时清扫；同一间隔下不重复调度。
   *
   * <p>热更只对未回收的旧 workspace 生效：下一次清扫读取最新保留期，仍在活动的 workspace 因 in-use lease 永不删除。测试构造无 workspace
   * 存储时只记录策略、不调度。
   */
  private void applyTemporaryResourcePolicy(long ttlSeconds, long cleanupIntervalSeconds) {
    temporaryResourceTtlSeconds = ttlSeconds;
    temporaryResourceCleanupIntervalSeconds = cleanupIntervalSeconds;
    if (textOutputStore == null) {
      return;
    }
    synchronized (temporaryResourcePolicyLock) {
      if (temporaryResourceSweep != null
          && scheduledCleanupIntervalSeconds == cleanupIntervalSeconds) {
        return;
      }
      scheduledCleanupIntervalSeconds = cleanupIntervalSeconds;
      if (temporaryResourceSweep != null) {
        temporaryResourceSweep.cancel(false);
        temporaryResourceSweep = null;
      }
      try {
        temporaryResourceSweep =
            scheduler.scheduleWithFixedDelay(
                this::submitTemporaryResourceSweep,
                cleanupIntervalSeconds,
                cleanupIntervalSeconds,
                TimeUnit.SECONDS);
      } catch (RejectedExecutionException ignored) {
        temporaryResourceSweep = null;
      }
    }
  }

  /** 把清扫派发到任务执行器，避免文件删除拖慢承载 heartbeat/reconnect 的单线程 scheduler。 */
  private void submitTemporaryResourceSweep() {
    try {
      taskExecutor.execute(this::sweepTemporaryResources);
    } catch (RejectedExecutionException ignored) {
      // 关闭中：忽略。
    }
  }

  /** 按最新保留期清扫过期 workspace；保留期尚未通告时不动。 */
  private void sweepTemporaryResources() {
    if (textOutputStore == null) {
      return;
    }
    long ttlSeconds = temporaryResourceTtlSeconds;
    if (ttlSeconds <= 0) {
      return;
    }
    textOutputStore.sweep(ttlSeconds);
  }

  /** 最近一次应用的临时资源保留期（秒）；仅用于测试断言策略热更是否生效。 */
  long temporaryResourceTtlSeconds() {
    return temporaryResourceTtlSeconds;
  }

  /** 最近一次应用的临时资源扫描间隔（秒）；仅用于测试断言策略热更是否生效。 */
  long temporaryResourceCleanupIntervalSeconds() {
    return temporaryResourceCleanupIntervalSeconds;
  }

  /** 递交一条上传票据：调用与传输共同关联，错误调用的票据不能完成其它调用的上传。 */
  private void requireInvocationIdAndDeliverTicket(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null || envelope.invocationId().isBlank()) {
      throw new DaemonProtocolException("RESOURCE_UPLOAD_TICKET must declare invocationId");
    }
    resourceTransferClient.onTicket(envelope.invocationId(), envelope.payloadJson());
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
    EnvironmentId bound = boundEnvironmentId.get();
    if (bound != null && envelope.environmentId() != null) {
      if (!bound.equals(envelope.environmentId())) {
        throw new DaemonProtocolException(
            "envelope environmentId does not match daemon: " + envelope.environmentId());
      }
    }
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
      // wire 的 timeoutMillis 是 Platform 已解析完成的唯一有效超时：Daemon 原样使用，不再回落或截断。
      Duration timeout = payload.timeout();
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
          null);
    }
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
        EnvironmentCapabilityTerminationCause.CANCELLED);
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
   * RunningInvocation#claimTerminal(EnvironmentCapabilityTerminationCause, DaemonTerminalMessage,
   * Runnable)} 仲裁本地执行资源；预检失败等尚未建立本地执行的路径直接进入 journal。两类路径最终都以 {@link
   * DaemonInvocationJournal#complete(String, DaemonTerminalMessage)} 的 RUNNING 到终态原子跃迁作为唯一提交点；
   * 只有提交成功者发送终态报文。
   *
   * <p>{@code cause} 非空表示这是运行时基于 deadline 或调用方取消做出的裁决。能力若声明了 {@link
   * EnvironmentCapabilityExecutionHandle#terminationGrace()}，运行时把终态提交让给它：由能力携带已捕获输出、在同一终态类型下收尾 （见
   * {@link RunningInvocation#resolveHandoff}）；预算到期仍未收尾时由运行时兜底提交，因此终态既不会因能力不配合而丢失，也不会无限延迟。
   */
  private void terminal(
      String invocationId,
      DaemonTerminalMessage message,
      EnvironmentCapabilityTerminationCause cause) {
    RunningInvocation invocation = running.get(invocationId);
    if (invocation != null
        && !invocation.claimTerminal(
            scheduler, cause, message, () -> commitTerminal(invocationId, invocation, message))) {
      return;
    }
    commitTerminal(invocationId, invocation, message);
  }

  /** 单点提交终态：journal 的 RUNNING 到终态跃迁是唯一仲裁点，只有跃迁成功者发送终态报文。 */
  private void commitTerminal(
      String invocationId, RunningInvocation invocation, DaemonTerminalMessage message) {
    if (!journal.complete(invocationId, message)) {
      return;
    }
    if (invocation == null) {
      running.remove(invocationId);
    } else {
      running.remove(invocationId, invocation);
    }
    send(message.messageType(), invocationId, message.payloadJson());
  }

  /**
   * 用能力收尾事实替换运行时终态的正文，同时保留运行时的终态类型。
   *
   * <p>终态类型表达裁决原因（超时仍是 {@code FAILED}、取消仍是 {@code CANCELLED}，平台据此走既有的失败/取消收敛路径），正文表达本地事实。
   * 这样已捕获输出无需协议变更即可到达调用方，也绝不会把一次取消报告成 {@code COMPLETED}。
   *
   * <p>注入的正文只来自能力结果的文本内容，与同一次调用原本会返回的工具结果正文完全一致：二进制/资源/JSON 内容不进入终态说明，因此不会出现 durable 日志之外的原始字节。超过单条
   * wire 文本上限的正文被整体丢弃（只保留运行时自己的裁决原因），绝不截断成半个字符，也绝不因此丢终态。
   */
  private DaemonTerminalMessage withCapabilityText(
      DaemonTerminalMessage base, String capabilityText) {
    String field = base.messageType() == DaemonMessageType.CANCELLED ? "reason" : "message";
    String baseText = payloadText(base.payloadJson(), field);
    String text =
        capabilityText == null
                || capabilityText.isBlank()
                || ResourceRef.utf8LengthUpTo(
                        capabilityText,
                        "capabilityText",
                        DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES)
                    > DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES
            ? baseText
            : baseText + "\n" + capabilityText;
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put(field, text);
    return new DaemonTerminalMessage(base.messageType(), payload.toString());
  }

  private String payloadText(String payloadJson, String field) {
    try {
      JsonNode payload = envelopeCodec.readJson(payloadJson);
      JsonNode value = payload.isObject() ? payload.get(field) : null;
      return value == null || !value.isTextual() ? "" : value.textValue();
    } catch (RuntimeException error) {
      // 运行时自己的终态正文必定可解析；解析失败时退化为只携带能力文本，绝不因此丢终态。
      return "";
    }
  }

  /** 汇总能力结果中模型可见的文本正文；非文本内容不进入终态说明。 */
  private static String capturedText(EnvironmentCapabilityResult result) {
    StringBuilder text = new StringBuilder();
    for (ResultContent content : result.contents()) {
      if (content instanceof TextResultContent textContent) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(textContent.text());
      }
    }
    return text.toString();
  }

  private static long deadlineNanos(Duration timeout) {
    try {
      return Math.addExact(System.nanoTime(), Math.multiplyExact(timeout.toMillis(), 1_000_000L));
    } catch (ArithmeticException overflow) {
      // wire 允许的超长 timeout 只表示「实际上无 deadline」；调用仍可由 CANCEL、连接停机或正常终态收敛。
      return Long.MAX_VALUE;
    }
  }

  /**
   * 为调用登记执行 deadline。
   *
   * <p>timeout 为 0 表示「没有 deadline」：绝不能调度一个立即触发的定时终态，调用只由 CANCEL、连接停机或能力自身终态收敛。
   */
  private void scheduleTimeout(RunningInvocation invocation, Duration timeout) {
    if (!isRunning(invocation.invocationId()) || invocation.isTerminal()) {
      return;
    }
    if (timeout.isZero()) {
      invocation.setDeadlineNanos(Long.MAX_VALUE);
      return;
    }
    invocation.setDeadlineNanos(deadlineNanos(timeout));
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
                      EnvironmentCapabilityTerminationCause.TIMED_OUT),
              timeout.toMillis(),
              TimeUnit.MILLISECONDS);
      invocation.setDeadline(deadline);
    } catch (RuntimeException error) {
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(
              DaemonMessageType.FAILED,
              "{\"message\":\"cannot schedule capability timeout: " + error.getMessage() + "\"}"),
          EnvironmentCapabilityTerminationCause.CANCELLED);
    }
  }

  private boolean isRunning(String invocationId) {
    return journal
        .find(invocationId)
        .map(entry -> entry.state() == DaemonInvocationState.RUNNING)
        .orElse(false);
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
        hello ? null : boundEnvironmentId.get(),
        invocationId,
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
                invocation.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
                journal.complete(
                    invocation.invocationId(),
                    new DaemonTerminalMessage(
                        DaemonMessageType.CANCELLED, "{\"reason\":\"daemon shutdown\"}"));
              });
      running.clear();
      // LSP 等 capability 资源在 transport 与任务收敛之后、执行资源关闭之前收尾。
      closeQuietly(capabilityResources);
      cancelTemporaryResourceSweep();
      shutdownExecutors(scheduler, taskExecutor, lspExecutor);
    } finally {
      termination.countDown();
    }
  }

  /** 取消定时清扫任务；已关闭后的重复调用无副作用。 */
  private void cancelTemporaryResourceSweep() {
    synchronized (temporaryResourcePolicyLock) {
      if (temporaryResourceSweep != null) {
        temporaryResourceSweep.cancel(false);
        temporaryResourceSweep = null;
      }
    }
  }

  @Override
  public void close() {
    shutdown(DaemonRuntimeState.STOPPED, null);
  }

  private static void shutdownExecutors(
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ExecutorService lspExecutor) {
    if (taskExecutor != null) {
      taskExecutor.shutdownNow();
      try {
        taskExecutor.awaitTermination(
            EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
    if (lspExecutor != null) {
      lspExecutor.shutdownNow();
      try {
        lspExecutor.awaitTermination(
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

    /**
     * 注册 capability，并返回需要与 runtime 同生命周期的进程内资源（例如 LSP 客户端池）。
     *
     * @param taskExecutor 通用任务执行器（虚拟线程）
     * @param lspExecutor LSP 客户端阻塞 I/O 使用的平台线程执行器
     * @return 由 runtime 关闭的资源；没有则为 {@code null}
     */
    AutoCloseable register(
        DaemonCapabilityRegistry registry,
        ExecutorService taskExecutor,
        ScheduledExecutorService scheduler,
        ExecutorService lspExecutor);
  }

  private static final class ActiveConnection {
    private final long generation;
    private final DaemonConnection connection;
    private final AtomicBoolean helloSent = new AtomicBoolean();
    private final AtomicBoolean welcomed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();

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

    /** 已发出的收尾请求原因；句柄尚未建立时据此在 {@link #setHandle} 中补发。 */
    private final AtomicReference<EnvironmentCapabilityTerminationCause> terminationCause =
        new AtomicReference<>();

    /**
     * 终态仲裁与资源上传控制帧出站的互斥门。
     *
     * <p>服务端只接受活动调用的上传控制帧，因此「检查调用仍活动 + 发送控制帧」必须与终态抢占互斥：控制帧要么完整发生在终态之前，要么确定不发送；终态绝不 会被之后的控制帧越过。
     *
     * <p>该门同时保护延迟终态窗口的开启与解析：运行时裁决与能力收尾对「谁是终态提交者」只能有一个结论，且窗口必须在请求能力收尾之前开启，否则与收尾竞态的能力终态会因窗口未开启而被丢弃。
     */
    private final Object terminalGate = new Object();

    /** 本次调用的绝对超时时刻；用于让终态编码阶段的上传不超过调用预算。 */
    private volatile long deadlineNanos = Long.MAX_VALUE;

    /** 非空表示延迟终态窗口已开启：终态改由能力收尾或兜底定时器提交。 */
    private DaemonTerminalMessage handoff;

    /** 延迟终态窗口的兜底定时器：到期后由运行时提交 {@link #handoff}。 */
    private volatile ScheduledFuture<?> handoffFallback;

    private RunningInvocation(String invocationId) {
      this.invocationId = invocationId;
    }

    private String invocationId() {
      return invocationId;
    }

    private long deadlineNanos() {
      return deadlineNanos;
    }

    private void setDeadlineNanos(long deadlineNanos) {
      this.deadlineNanos = deadlineNanos;
    }

    private boolean isTerminal() {
      return terminal.get();
    }

    /**
     * 尝试原子抢占终态仲裁权（terminal-once 互斥点），并决定终态由谁提交。
     *
     * <p>抢占与资源上传控制帧出站共享 {@link #terminalGate}：终态一旦被抢占，该调用之后的控制帧一律确定不发送；已在临界区内入队的控制帧必然排在终态报文 之前。
     *
     * <p>抢占成功且能力声明了收尾预算时，本方法在临界区内开启延迟终态窗口并向能力发出带原因的收尾请求：窗口先于请求开启，因此能力收尾提交的终态一定 能接管提交权。窗口到期仍未收尾时由
     * {@code fallback} 提交运行时自己的终态。
     *
     * @param cause 非空表示运行时基于 deadline 或取消裁决，需要请求底层能力收尾；null 表示无需请求能力
     * @param message 运行时自己的终态，同时是延迟终态窗口到期后的兜底终态
     * @param fallback 延迟终态窗口到期后提交兜底终态的动作
     * @return true 表示调用方必须立即提交 {@code message}；false 表示已有终态抢先，或终态已交由延迟窗口提交
     */
    private boolean claimTerminal(
        ScheduledExecutorService scheduler,
        EnvironmentCapabilityTerminationCause cause,
        DaemonTerminalMessage message,
        Runnable fallback) {
      boolean deferred;
      synchronized (terminalGate) {
        if (!terminal.compareAndSet(false, true)) {
          return false;
        }
        deferred = openHandoff(scheduler, message, fallback);
      }
      cancelDeadline();
      if (cause != null) {
        terminate(cause);
      }
      return !deferred;
    }

    /**
     * 在终态互斥门内开启延迟终态窗口。
     *
     * <p>只在能力明确声明正预算时开启：普通能力立即由运行时收敛，不引入任何等待。兜底定时器登记失败（scheduler 已停机）时不开启窗口，
     * 由运行时立即收敛自己的终态，绝不让终态因调度器不可用而悬挂。
     *
     * @return true 表示窗口已开启，终态提交权已让给能力收尾或兜底定时器
     */
    private boolean openHandoff(
        ScheduledExecutorService scheduler, DaemonTerminalMessage message, Runnable fallback) {
      EnvironmentCapabilityExecutionHandle current = handle.get();
      if (current == null) {
        return false;
      }
      Duration grace = current.terminationGrace();
      if (grace == null || grace.isZero() || grace.isNegative()) {
        return false;
      }
      handoff = message;
      try {
        handoffFallback =
            scheduler.schedule(fallback, Math.max(0L, grace.toMillis()), TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException error) {
        handoff = null;
        return false;
      }
      return true;
    }

    /**
     * 能力在收尾预算内提交终态：用能力事实构造的终态接管提交权。
     *
     * <p>只在延迟终态窗口内生效；窗口不存在（或已被兜底定时器收口）时返回 false，调用方按正常终态路径处理。
     *
     * @param capabilityTerminal 以窗口内运行时的裁决终态为输入构造最终终态报文
     * @return true 表示终态已由本方法提交
     */
    private boolean resolveHandoff(
        UnaryOperator<DaemonTerminalMessage> capabilityTerminal,
        Consumer<DaemonTerminalMessage> committer) {
      DaemonTerminalMessage resolved;
      synchronized (terminalGate) {
        if (handoff == null) {
          return false;
        }
        resolved = capabilityTerminal.apply(handoff);
        handoff = null;
        ScheduledFuture<?> fallback = handoffFallback;
        handoffFallback = null;
        if (fallback != null) {
          fallback.cancel(false);
        }
      }
      committer.accept(resolved);
      return true;
    }

    /**
     * 在终态互斥门内执行一次资源上传控制帧的出站动作。
     *
     * @param sender 「检查调用仍活动并发送控制帧」的动作；返回 false 表示帧确定未发送
     * @return 传送动作的结果；终态已被抢占时返回 false 且绝不执行动作
     */
    private boolean trySendControl(BooleanSupplier sender) {
      synchronized (terminalGate) {
        if (terminal.get()) {
          return false;
        }
        return sender.getAsBoolean();
      }
    }

    private boolean begin() {
      return begun.compareAndSet(false, true);
    }

    private void setHandle(EnvironmentCapabilityExecutionHandle handle) {
      this.handle.set(handle);
      EnvironmentCapabilityTerminationCause pending = terminationCause.get();
      if (pending != null) {
        handle.terminate(pending);
      }
    }

    private void setDeadline(ScheduledFuture<?> deadline) {
      this.deadline.set(deadline);
    }

    /** 请求底层能力以明确原因收尾；原因在句柄尚未建立时被记住，句柄建立后立即补发。 */
    private void terminate(EnvironmentCapabilityTerminationCause cause) {
      if (cancelled.compareAndSet(false, true)) {
        terminationCause.set(cause);
        EnvironmentCapabilityExecutionHandle current = handle.get();
        if (current != null) {
          current.terminate(cause);
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
        null);
  }

  /**
   * 递交一条资源上传控制帧（{@code RESOURCE_UPLOAD_REQUEST}/{@code COMMIT}）。
   *
   * <p>控制帧的「检查调用仍活动」与「发送」必须对该 invocation 的终态抢占互斥：服务端只接受活动调用的上传控制帧，若上传控制帧排在终态之后发出， 连接会被判定为
   * 协议违规而关闭。因此本方法在 {@link RunningInvocation#trySendControl} 的临界区内完成检查与发送，胜者顺序为：
   *
   * <ol>
   *   <li>控制帧先取得临界区：发送/入队发生在终态可被抢占之前；
   *   <li>终态先取得临界区：本调用之后的全部控制帧返回 false 且确定未发送。
   * </ol>
   *
   * <p>控制帧也只能由已 READY 的连接发出：连接尚未就绪时返回 false（帧确定未发送），上传客户端据此按同一 transfer 重试。
   */
  private boolean sendTransferControl(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    if (state != DaemonRuntimeState.READY || boundEnvironmentId.get() == null) {
      return false;
    }
    RunningInvocation invocation = running.get(invocationId);
    if (invocation == null) {
      return false;
    }
    return invocation.trySendControl(
        () -> isRunning(invocationId) && send(messageType, invocationId, payloadJson));
  }

  /**
   * 直传字节到全局对象存储的上传端口，绑定被调用的 invocation 的等待边界。
   *
   * <p>超时预算在终态编码开始时读取一次，因此上传的等待上限与该调用剩余的有效 deadline 一致；WELCOME 尚未通告预算或超出预算时确定性失败，绝不静默降级。
   */
  private DaemonResourceUploader resourceUploader(RunningInvocation invocation) {
    boolean budgetAvailable = maxResourceBytes.get() > 0;
    long deadlineNanos = invocation.deadlineNanos();
    return resourceTransferClient.uploaderFor(
        new DaemonResourceTransferClient.Deadline() {
          @Override
          public boolean canContinue() {
            return budgetAvailable
                && !invocation.isTerminal()
                && isRunning(invocation.invocationId())
                && System.nanoTime() < deadlineNanos;
          }

          @Override
          public long remainingMillis() {
            if (deadlineNanos == Long.MAX_VALUE) {
              return Long.MAX_VALUE;
            }
            return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
          }
        });
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
            EnvironmentCapabilityTerminationCause.CANCELLED);
        return;
      }
      try {
        String payloadJson = resultCodec.encodeProgress(partial);
        send(DaemonMessageType.PROGRESS, invocation.invocationId(), payloadJson);
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
            null);
        return;
      }
      try {
        String payloadJson =
            resultCodec.encodeCompleted(
                result, maxResourceBytes.get(), resourceUploader(invocation));
        DaemonTerminalMessage completed =
            new DaemonTerminalMessage(DaemonMessageType.COMPLETED, payloadJson);
        // 处于超时/取消的延迟终态窗口时，用已捕获输出在同一裁决类型下收尾；否则按正常终态路径提交。
        if (!invocation.resolveHandoff(
            runtime -> withCapabilityText(runtime, capturedText(result)),
            resolved -> commitTerminal(invocation.invocationId(), invocation, resolved))) {
          terminal(invocation.invocationId(), completed, null);
        }
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "complete");
      }
    }

    @Override
    public void onError(Throwable error) {
      DaemonTerminalMessage failed =
          new DaemonTerminalMessage(DaemonMessageType.FAILED, errorPayload(error));
      if (!invocation.resolveHandoff(
          runtime -> withCapabilityText(runtime, safeFailureMessage(error)),
          resolved -> commitTerminal(invocation.invocationId(), invocation, resolved))) {
        terminal(invocation.invocationId(), failed, null);
      }
    }
  }
}
