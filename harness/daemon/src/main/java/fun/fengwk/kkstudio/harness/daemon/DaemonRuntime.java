package fun.fengwk.kkstudio.harness.daemon;

import okhttp3.OkHttpClient;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingCapabilities;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.LspService;
import fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillPackageInstaller;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.OkHttpWebSocketTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Environment Daemon 的编排基座：装配连接会话、调用执行、受管更新与临时资源策略，并派发入站协议帧。
 *
 * <p>本类只做编排与装配，不再直接持有连接代际、Invocation journal、执行句柄、更新回执或临时资源策略：这些状态各自归属 {@link
 * DaemonConnectionSession}、{@link DaemonInvocationCoordinator}、{@link DaemonUpdateCoordinator} 与
 * {@link DaemonTemporaryResourcePolicy}，单一所有者、互不回填。本类只桥接它们之间的回调与出站端口。
 *
 * <p>Invocation journal 是进程内执行事实源；WebSocket 连接仅作为消息传输管道。连接断开后 Daemon 保留 journal 事实并重新握手与重连；若
 * Gateway 再次发送相同 {@code invocationId}（包括物理连接丢失后的同实例重连重放），RUNNING 与 terminal journal 条目分别重放 STARTED
 * 与 terminal 报文，绝不重复执行副作用。
 *
 * <p>Daemon 拥有两个执行生命周期资源：单线程 scheduler 处理 heartbeat、reconnect 与 timeout，共享的 virtual-thread-per-task
 * executor 处理 Coding/目录浏览等阻塞调用与 Git 网络取消观察。transport/JDK 内部线程不在该生命周期内；执行器只在本类创建， 其余组件只注入使用。
 */
public final class DaemonRuntime implements AutoCloseable {

  private static final Duration EXECUTOR_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

  private final DaemonConnectionSession session;
  private final DaemonInvocationCoordinator invocations;
  private final DaemonUpdateCoordinator updates;
  private final DaemonTemporaryResourcePolicy temporaryResources;
  private final DaemonResourceTransferClient resourceTransferClient;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();

  /** capability 持有的进程内资源（当前是 LSP 客户端池）；由本运行时负责在 shutdown 时关闭。 */
  private final AutoCloseable capabilityResources;

  private final ScheduledExecutorService scheduler;
  private final ExecutorService taskExecutor;
  private final ExecutorService lspExecutor;

  /**
   * 创建完整生产运行时。LSP 服务、scheduler 与 virtual-thread-per-task executor 在注册 capability 前创建并注入，成功后由
   * runtime 独占生命周期；任一步构造失败都会释放 transport、LSP 客户端和已创建的执行资源。
   *
   * <p>构造失败与清理失败共用同一优先级：原失败保持主因（Error 保持对象身份），清理失败作为 suppressed 附加且不跳过其它资源。
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
    try {
      taskExecutor = newTaskExecutor();
      lspExecutor = newLspExecutor();
      transport = new OkHttpWebSocketTransport(config.gatewayUri());
      DaemonCapabilityRegistry capabilityRegistry = new DaemonCapabilityRegistry();
      capabilityResources =
          registrar.register(capabilityRegistry, taskExecutor, scheduler, lspExecutor);
      return new DaemonRuntime(
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
    } catch (Throwable constructionFailure) {
      // 部分构造失败：原失败是主因，清理失败按同一优先级并入且不跳过任何已创建资源。
      DaemonShutdownFailures failures = new DaemonShutdownFailures();
      failures.record("construction", constructionFailure);
      closeResource(capabilityResources, "capability resource close", failures);
      closeResource(transport, "transport close", failures);
      shutdownExecutors(scheduler, taskExecutor, lspExecutor, failures);
      Error fatal = failures.error();
      if (fatal != null) {
        throw fatal;
      }
      throw failures.exception();
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
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    this.lspExecutor = lspExecutor;
    this.capabilityResources = capabilityResources;
    OkHttpClient resourceHttpClient =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    this.resourceTransferClient =
        new DaemonResourceTransferClient(resourceHttpClient, this::sendTransferControl);
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    DaemonEnvironmentInfo environmentInfo =
        detectEnvironmentInfo(
            config, operatingSystem, controlledTemporaryDirectory(textOutputStore, config));
    this.temporaryResources =
        new DaemonTemporaryResourcePolicy(scheduler, taskExecutor, textOutputStore);
    this.session =
        new DaemonConnectionSession(
            config, transport, environmentInfo, scheduler, new SessionHandler());
    this.invocations =
        new DaemonInvocationCoordinator(
            capabilityRegistry,
            journal,
            scheduler,
            session::isStarted,
            session::send,
            new ResourceUploads());
    this.updates =
        new DaemonUpdateCoordinator(
            config.dataDir(),
            operatingSystem,
            taskExecutor,
            session::send,
            () -> session.state() == DaemonRuntimeState.READY);
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
    session.start();
  }

  /** 阻塞等待 daemon 终止（由 close 或终态冲突触发）；返回终态。 */
  public DaemonRuntimeState awaitTermination() throws InterruptedException {
    return session.awaitTermination();
  }

  /** 返回当前生命周期状态。 */
  public DaemonRuntimeState state() {
    return session.state();
  }

  /** 返回终态失败原因；非 FAILED 时为 {@code null}。 */
  public String failureReason() {
    return session.failureReason();
  }

  /** 测试注入点：替换更新准备器，避免真实网络下载。 */
  void setManagedUpdater(ManagedUpdatePreparer updater) {
    updates.setPreparer(updater);
  }

  /** 测试注入点：替换分离更新器启动器，避免真实 fork 进程。 */
  void setUpdateLauncher(ManagedUpdateLauncher launcher) {
    updates.setLauncher(launcher);
  }

  /** 最近一次应用的临时资源保留期（秒）；仅用于测试断言策略热更是否生效。 */
  long temporaryResourceTtlSeconds() {
    return temporaryResources.ttlSeconds();
  }

  /** 最近一次应用的临时资源扫描间隔（秒）；仅用于测试断言策略热更是否生效。 */
  long temporaryResourceCleanupIntervalSeconds() {
    return temporaryResources.cleanupIntervalSeconds();
  }

  private void dispatch(DaemonEnvelope envelope) {
    switch (envelope.messageType()) {
      case INVOKE -> {
        requireInvocationId(envelope);
        invocations.handleInvoke(envelope);
      }
      case CANCEL -> {
        requireInvocationId(envelope);
        invocations.handleCancel(envelope);
      }
      case TEMPORARY_RESOURCE_POLICY -> handleTemporaryResourcePolicy(envelope);
      case UPDATE -> updates.handleUpdate(envelope);
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
  }

  /**
   * 应用服务端在同一控制通道上推送的临时资源策略热更新；只在 WELCOME 建立绑定后接受。
   *
   * <p>已 READY 的连接不会再次收到 WELCOME，因此设置热更经 {@code TEMPORARY_RESOURCE_POLICY} 帧到达，幂等地重排清扫任务。
   */
  private void handleTemporaryResourcePolicy(DaemonEnvelope envelope) {
    if (session.state() != DaemonRuntimeState.READY || envelope.environmentId() == null) {
      throw new DaemonProtocolException(
          "TEMPORARY_RESOURCE_POLICY requires a READY bound connection");
    }
    ObjectNode payload = new DaemonEnvelopeCodec().readPayload(envelope);
    long ttlSeconds =
        DaemonProtocolPayloads.requiredPositiveLong(
            payload, "TEMPORARY_RESOURCE_POLICY", "temporaryResourceTtlSeconds");
    long cleanupIntervalSeconds =
        DaemonProtocolPayloads.requiredPositiveLong(
            payload, "TEMPORARY_RESOURCE_POLICY", "temporaryResourceCleanupIntervalSeconds");
    temporaryResources.apply(ttlSeconds, cleanupIntervalSeconds);
  }

  /** 递交一条上传票据：调用与传输共同关联，错误调用的票据不能完成其它调用的上传。 */
  private void requireInvocationIdAndDeliverTicket(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null || envelope.invocationId().isBlank()) {
      throw new DaemonProtocolException("RESOURCE_UPLOAD_TICKET must declare invocationId");
    }
    resourceTransferClient.onTicket(envelope.invocationId(), envelope.payloadJson());
  }

  private void requireInvocationId(DaemonEnvelope envelope) {
    if (envelope.invocationId() == null) {
      throw new DaemonProtocolException(envelope.messageType() + " requires non-null invocationId");
    }
  }

  /**
   * 递交一条资源上传控制帧（{@code RESOURCE_UPLOAD_REQUEST}/{@code COMMIT}）。
   *
   * <p>控制帧也只能由已 READY 的连接发出：连接尚未就绪时返回 false（帧确定未发送），上传客户端据此按同一 transfer 重试。控制帧与调用终态的互斥由调用协调器保证。
   */
  private boolean sendTransferControl(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    if (session.state() != DaemonRuntimeState.READY || session.boundEnvironmentId() == null) {
      return false;
    }
    return invocations.sendControlWhileActive(messageType, invocationId, payloadJson);
  }

  /**
   * 关停：只有赢得关闭权的调用者继续执行，顺序固定为连接/transport → 全部调用 → capability 资源 → 临时资源 → 全部执行器。
   *
   * <p>每一项无论 {@link RuntimeException}、{@link Error} 还是 checked 关闭失败都继续后续动作，失败按统一优先级聚合；终止闩始终在
   * finally 中释放，因此同步抛出的聚合失败不会让 {@link #awaitTermination()} 悬挂。任一关停失败都会把会话终态发布为 FAILED 与固定 安全原因。
   */
  private void shutdown(DaemonRuntimeState terminalState, String reason) {
    if (!session.beginShutdown(terminalState, reason)) {
      return;
    }
    DaemonShutdownFailures failures = new DaemonShutdownFailures();
    try {
      session.releaseShutdownResources(failures);
      invocations.shutdown(failures);
      closeResource(capabilityResources, "capability resource close", failures);
      closeResource(temporaryResources, "temporary resource close", failures);
      shutdownExecutors(scheduler, taskExecutor, lspExecutor, failures);
    } finally {
      session.completeShutdown(failures);
    }
    if (!failures.hasFailure()) {
      return;
    }
    Error fatal = failures.error();
    if (fatal != null) {
      throw fatal;
    }
    throw failures.exception();
  }

  @Override
  public void close() {
    shutdown(DaemonRuntimeState.STOPPED, null);
  }

  /**
   * 关闭全部执行器：先对每个执行器请求立即停止，再分别等待既有预算。
   *
   * <p>任一步失败都按固定阶段记入聚合并继续，绝不跳过其余执行器：等待超时（{@code false}）不是真正终止，中断只被记录且不影响其余等待， 并在全部等待结束后恢复。
   */
  private static void shutdownExecutors(
      ScheduledExecutorService scheduler,
      ExecutorService taskExecutor,
      ExecutorService lspExecutor,
      DaemonShutdownFailures failures) {
    stopExecutor(taskExecutor, "task executor shutdown", failures);
    stopExecutor(lspExecutor, "lsp executor shutdown", failures);
    stopExecutor(scheduler, "scheduler shutdown", failures);
    boolean interrupted = false;
    interrupted |= awaitExecutorTermination(taskExecutor, "task executor shutdown", failures);
    interrupted |= awaitExecutorTermination(lspExecutor, "lsp executor shutdown", failures);
    interrupted |= awaitExecutorTermination(scheduler, "scheduler shutdown", failures);
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void stopExecutor(
      ExecutorService executor, String phase, DaemonShutdownFailures failures) {
    if (executor == null) {
      return;
    }
    try {
      executor.shutdownNow();
    } catch (Throwable error) {
      failures.record(phase, error);
    }
  }

  /**
   * 等待一个执行器在既有预算内终止。
   *
   * @return true 表示等待被中断：中断只被记录，调用方在全部执行器等待结束后恢复中断标记
   */
  private static boolean awaitExecutorTermination(
      ExecutorService executor, String phase, DaemonShutdownFailures failures) {
    if (executor == null) {
      return false;
    }
    try {
      if (executor.awaitTermination(
          EXECUTOR_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        return false;
      }
      failures.record(phase, new IllegalStateException(phase + " did not terminate in time"));
      return false;
    } catch (InterruptedException error) {
      failures.record(phase, error);
      return true;
    } catch (Throwable error) {
      failures.record(phase, error);
      return false;
    }
  }

  /** 关闭一个生命周期资源：失败按固定阶段记入聚合，绝不阻断其余清理。 */
  private static void closeResource(
      AutoCloseable resource, String phase, DaemonShutdownFailures failures) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Throwable error) {
      failures.record(phase, error);
    }
  }

  /** 连接会话回调：把连接级事实桥接到资源、更新与调用协调器。 */
  private final class SessionHandler implements DaemonConnectionSession.Handler {

    @Override
    public void onEnvelope(DaemonEnvelope envelope) {
      dispatch(envelope);
    }

    @Override
    public void onWelcomePayload(
        long maxResourceBytes, long ttlSeconds, long cleanupIntervalSeconds) {
      resourceTransferClient.announceBudget(maxResourceBytes);
      temporaryResources.apply(ttlSeconds, cleanupIntervalSeconds);
    }

    @Override
    public void onReady() {
      // 只有 READY 连接才能递交上传控制帧；断开不改变已接受的更新事实，重连后重发已知回执。
      resourceTransferClient.onConnectionReady();
      updates.resendAfterReconnect();
    }

    @Override
    public void onConnectionLost() {
      resourceTransferClient.onConnectionLost();
    }

    @Override
    public void onTerminalFailure(String reason) {
      shutdown(DaemonRuntimeState.FAILED, reason);
    }
  }

  /** 调用终态编码所需的资源上传端口：预算在创建端口时读取一次，等待边界由调用协调器提供。 */
  private final class ResourceUploads implements DaemonInvocationUploads {

    @Override
    public long maxResourceBytes() {
      return resourceTransferClient.budgetBytes();
    }

    @Override
    public DaemonResourceUploader uploaderFor(Deadline deadline) {
      boolean budgetAvailable = resourceTransferClient.budgetBytes() > 0;
      return resourceTransferClient.uploaderFor(
          new DaemonResourceTransferClient.Deadline() {
            @Override
            public boolean canContinue() {
              return budgetAvailable && deadline.canContinue();
            }

            @Override
            public long remainingMillis() {
              return deadline.remainingMillis();
            }
          });
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
}
