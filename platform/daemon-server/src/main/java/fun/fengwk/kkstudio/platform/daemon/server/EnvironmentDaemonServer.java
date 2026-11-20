package fun.fengwk.kkstudio.platform.daemon.server;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ResourceDownload;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.platform.daemon.DaemonWorkdirSyntax;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.ExpectedFile;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.Invoke;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.Recover;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceBudgets;
import fun.fengwk.kkstudio.platform.daemon.protocol.Settings;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnels;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Environment daemon 的服务端会话核心：组合准入与会话注册、单连接会话、调用跟踪、上传协调、IDE 命令与更新控制。 */
public final class EnvironmentDaemonServer
    implements DaemonSessions, EnvironmentTunnels, EnvironmentCapabilityTransport {

  private static final Logger LOG = System.getLogger(EnvironmentDaemonServer.class.getName());

  private record PendingAdmission(UUID leaseToken, String registrationToken) {}

  private final DaemonLeaseStore leaseStore;
  private final DaemonRegistrationDirectory registrationDirectory;
  private final EnvironmentSessionListener sessionListener;
  private final EnvironmentUpdateListener updateListener;
  private final DaemonResourceTicketService ticketService;
  private final Supplier<EnvironmentServerSettings> settingsSupplier;
  private final EnvironmentConfigSource configSource;
  private final Executor configExecutor;
  private final ExecutionExitReceiptLookup exitReceiptLookup;

  private final DaemonInvocationTracker invocationTracker = new DaemonInvocationTracker();
  private final DaemonTransferCoordinator transferCoordinator;
  private final DaemonIdeCoordinator ideCoordinator = new DaemonIdeCoordinator();
  private final DaemonUpdateCoordinator updateCoordinator = new DaemonUpdateCoordinator();

  private final Object inventory = new Object();
  private final Map<EnvironmentId, DaemonConnectionSession> sessions = new HashMap<>();
  private final Map<EnvironmentId, String> daemonInstances = new HashMap<>();
  private final ConfigSyncCoordinator configSyncs = new ConfigSyncCoordinator();
  private final Map<DaemonAdmission.Admitted, PendingAdmission> pendingAdmissions =
      new ConcurrentHashMap<>();
  private final AtomicLong connectionGenerations = new AtomicLong();
  private volatile boolean draining;

  public EnvironmentDaemonServer(
      DaemonLeaseStore leaseStore,
      DaemonRegistrationDirectory registrationDirectory,
      EnvironmentConfigSource configSource,
      EnvironmentSessionListener sessionListener,
      EnvironmentUpdateListener updateListener,
      DaemonResourceTicketService ticketService,
      Supplier<EnvironmentServerSettings> settings,
      Executor configExecutor,
      ExecutionExitReceiptLookup exitReceiptLookup,
      Clock clock) {
    this.transferCoordinator =
        new DaemonTransferCoordinator(Objects.requireNonNull(clock, "clock"));
    this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    this.registrationDirectory =
        Objects.requireNonNull(registrationDirectory, "registrationDirectory");
    this.configSource = Objects.requireNonNull(configSource, "configSource");
    this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener");
    this.updateListener = Objects.requireNonNull(updateListener, "updateListener");
    this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    this.settingsSupplier = Objects.requireNonNull(settings, "settings");
    this.configExecutor = Objects.requireNonNull(configExecutor, "configExecutor");
    this.exitReceiptLookup = Objects.requireNonNull(exitReceiptLookup, "exitReceiptLookup");
  }

  @Override
  public DaemonAdmission admit(DaemonHandshake handshake) {
    Objects.requireNonNull(handshake, "handshake");
    if (draining) {
      return new DaemonAdmission.RetryLater(Duration.ofSeconds(5));
    }
    String token = handshake.registrationToken();
    String instanceId = handshake.daemonInstanceId();
    EnvironmentId environmentId = handshake.environmentId();

    Optional<DaemonRegistration> registrationOpt =
        registrationDirectory.findByRegistrationToken(token);
    if (registrationOpt.isEmpty() || !environmentId.equals(registrationOpt.get().environmentId())) {
      return new DaemonAdmission.Rejected();
    }

    DaemonConnectionSession existing = sessionOf(environmentId);
    if (existing != null && existing.isReady()) {
      boolean activeToken;
      try {
        activeToken = leaseStore.hasActiveLeaseToken(environmentId, existing.leaseToken());
      } catch (RuntimeException error) {
        throw new ControlProtocolException("database unavailable: " + error.getMessage(), error);
      }
      if (activeToken) {
        return new DaemonAdmission.RetryLater(Duration.ofSeconds(5));
      }
    }

    LeaseBindResult bind =
        leaseStore.tryAcquire(environmentId, token, settings().heartbeatTimeout());
    return switch (bind) {
      case LeaseBindResult.Rejected rejected -> new DaemonAdmission.Rejected();
      case LeaseBindResult.RetryLater retryLater -> new DaemonAdmission.RetryLater(
          Duration.ofSeconds(5));
      case LeaseBindResult.Acquired acquired -> {
        DaemonConnectionSession previous = sessionOf(environmentId);
        if (previous != null) {
          previous.close();
        }
        if (replaceDaemonInstance(environmentId, instanceId) != null) {
          abandonInvocationsForChangedInstance(environmentId);
        }
        DaemonAdmission.Admitted admitted = new DaemonAdmission.Admitted(environmentId, instanceId);
        pendingAdmissions.put(admitted, new PendingAdmission(acquired.leaseToken(), token));
        yield admitted;
      }
    };
  }

  /**
   * Daemon 实例已更换：在途调用不可能再收到原实例的回执，逐个以“结果不确定”失败并释放其传输资源。
   *
   * <p>通知失败只记录告警，不影响其余调用与准入流程。
   */
  private void abandonInvocationsForChangedInstance(EnvironmentId environmentId) {
    for (ActiveInvocation active : invocationTracker.takeAbandonedInvocations(environmentId)) {
      try {
        active.listener.onError(
            new EnvironmentCapabilitySendUncertainException(
                "Environment daemon instance changed for "
                    + environmentId
                    + "; capability invocation outcome is uncertain."));
      } catch (RuntimeException error) {
        LOG.log(
            Level.WARNING,
            "failed to notify abandoned invocation "
                + active.invocationId
                + " on "
                + environmentId
                + ": "
                + error.getClass().getSimpleName());
      }
      transferCoordinator.releaseAllTransfers(active, ticketService);
    }
  }

  @Override
  public void abandon(DaemonAdmission.Admitted admitted) {
    Objects.requireNonNull(admitted, "admitted");
    PendingAdmission pending = pendingAdmissions.remove(admitted);
    if (pending != null) {
      try {
        leaseStore.disconnect(admitted.environmentId(), pending.leaseToken, Duration.ZERO);
      } catch (RuntimeException error) {
        LOG.log(
            Level.WARNING,
            "failed to disconnect abandoned admission for "
                + admitted.environmentId()
                + ": "
                + error.getClass().getSimpleName());
      }
    }
  }

  @Override
  public DaemonSession open(
      DaemonAdmission.Admitted admitted, DaemonControlChannel control, EnvironmentTunnel tunnel) {
    Objects.requireNonNull(admitted, "admitted");
    Objects.requireNonNull(control, "control");
    PendingAdmission pending = pendingAdmissions.remove(admitted);
    if (pending == null) {
      throw new IllegalStateException("no active admission found for " + admitted);
    }
    long gen = connectionGenerations.incrementAndGet();
    DaemonConnectionSession session =
        new DaemonConnectionSession(
            gen,
            admitted.environmentId(),
            admitted.daemonInstanceId(),
            pending.leaseToken,
            control,
            tunnel,
            leaseStore,
            configSource,
            sessionListener,
            updateListener,
            ticketService,
            settingsSupplier,
            exitReceiptLookup,
            invocationTracker,
            transferCoordinator,
            ideCoordinator,
            updateCoordinator,
            this::removeSession);
    EnvironmentServerSettings currentSettings = settings();
    session.initializeSettings(currentSettings);

    Envelope settingsEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.SETTINGS)
            .setEnvironmentId(admitted.environmentId().value().toString())
            .setSettings(settingsMessage(currentSettings))
            .build();

    synchronized (inventory) {
      sessions.put(admitted.environmentId(), session);
    }
    if (!session.offer(settingsEnvelope)) {
      session.close();
      return session;
    }
    session.sendInitialEnvironmentConfig();
    return session;
  }

  static Settings settingsMessage(EnvironmentServerSettings settings) {
    return Settings.newBuilder()
        .setResourceBudgets(
            ResourceBudgets.newBuilder()
                .setTemporaryResourceTtlSeconds(settings.temporaryResourceTtlSeconds())
                .setTemporaryResourceCleanupIntervalSeconds(
                    settings.temporaryResourceCleanupIntervalSeconds()))
        .build();
  }

  /** 配置变更通知：只对当前持有本地会话的 Environment 重新读取权威配置。 */
  public void environmentChanged(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    if (sessionOf(environmentId) == null) {
      return;
    }
    scheduleConfigSync(environmentId);
  }

  /** 系统设置变化时对在线 Daemon 重新下发 SETTINGS。 */
  public void resyncSettings() {
    List<DaemonConnectionSession> current;
    synchronized (inventory) {
      current = List.copyOf(sessions.values());
    }
    for (DaemonConnectionSession session : current) {
      if (!session.refreshSettings()) {
        LOG.log(Level.WARNING, "cannot refresh SETTINGS for " + session.identity());
      }
    }
  }

  /** LISTEN 重连或 resync：对本节点当前持有的全部会话重新读取权威配置。 */
  public void resyncEnvironmentConfigs() {
    List<EnvironmentId> current;
    synchronized (inventory) {
      current = List.copyOf(sessions.keySet());
    }
    for (EnvironmentId environmentId : current) {
      scheduleConfigSync(environmentId);
    }
  }

  private void scheduleConfigSync(EnvironmentId environmentId) {
    ConfigSyncCoordinator.State state = configSyncs.schedule(environmentId);
    if (state == null) {
      return;
    }
    try {
      configExecutor.execute(() -> runConfigSync(environmentId, state));
    } catch (RuntimeException error) {
      configSyncs.rejected(environmentId, state);
      DaemonFailures.log(
          EnvironmentDaemonServer.class,
          "environment config scheduling for " + environmentId,
          error);
    }
  }

  private void runConfigSync(EnvironmentId environmentId, ConfigSyncCoordinator.State state) {
    while (configSyncs.begin(environmentId, state)) {
      try {
        DaemonConnectionSession session = sessionOf(environmentId);
        if (session != null) {
          session.reconcileEnvironmentConfig();
        }
      } catch (RuntimeException error) {
        DaemonFailures.log(
            EnvironmentDaemonServer.class,
            "environment config reconciliation for " + environmentId,
            error);
      }
      if (!configSyncs.repeat(environmentId, state)) {
        return;
      }
    }
  }

  @Override
  public Optional<EnvironmentTunnel> local(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session != null && session.isReady() && session.tunnel() != null) {
      return Optional.of(session.tunnel());
    }
    return Optional.empty();
  }

  /**
   * 建立一条在途调用记录：心跳超时后由 {@code expire} 收尾，会话查找始终回到当前绑定。
   *
   * <p>此处只构造状态，不发帧；调用方在同一把会话锁内完成登记与发送。
   */
  private ActiveInvocation newActiveInvocation(
      EnvironmentId environmentId,
      UUID invocationId,
      EnvironmentCapabilityExecutionListener listener,
      DaemonConnectionSession session) {
    return new ActiveInvocation(
        environmentId,
        invocationId,
        listener,
        settings().heartbeatTimeout(),
        session.daemonInstanceId(),
        this::expire,
        () -> sessionOf(environmentId));
  }

  /** 取本环境已 READY 的会话；缺失或未就绪都视为该能力暂不可用。 */
  private DaemonConnectionSession requireReadySession(
      EnvironmentId environmentId, String capabilityId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null || !session.isReady()) {
      throw unavailable(environmentId, capabilityId);
    }
    return session;
  }

  /**
   * 确认会话仍持有权威 ready 租约。
   *
   * <p>租约查询失败视为环境暂不可用，不降级为“允许调用”：调用必须在权威租约成立时才下发。
   */
  private void requireHeldLease(EnvironmentId environmentId, UUID leaseToken, String capabilityId) {
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw unavailable(environmentId, capabilityId);
    }
  }

  @Override
  public EnvironmentCapabilityExecutionHandle invoke(
      EnvironmentId environmentId,
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    EnvironmentCapabilityDescriptor descriptor = request.descriptor();
    EnvironmentCapabilityDescriptor catalogDescriptor =
        EnvironmentCapabilityCatalog.require(descriptor.id());
    if (!catalogDescriptor.equals(descriptor)) {
      throw new IllegalArgumentException(
          "capability descriptor does not match EnvironmentCapabilityCatalog: " + descriptor.id());
    }
    UUID invocationId = Uuids.parseCanonical(request.call().id(), "call.id");

    if (updateCoordinator.isUpdating(environmentId)) {
      throw new EnvironmentCapabilityBusyException(
          "environment " + environmentId + " is being updated");
    }
    DaemonConnectionSession session = requireReadySession(environmentId, descriptor.id().value());
    validateRequiredWorkdirShape(session.daemonOperatingSystem(), descriptor, request.call());
    UUID leaseToken = session.leaseToken();
    requireHeldLease(environmentId, leaseToken, descriptor.id().value());

    ActiveInvocation active = newActiveInvocation(environmentId, invocationId, listener, session);
    ResourceDownload download = request.resourceDownload();
    if (download != null) {
      active.downloadTransfers.put(download.transferId(), download.blobId());
    }

    DaemonOfferResult outcome;
    RuntimeException sendFailure = null;
    synchronized (session.stateLock()) {
      if (!session.isReady() || !leaseToken.equals(session.leaseToken())) {
        throw unavailable(environmentId, descriptor.id().value());
      }
      if (updateCoordinator.isUpdating(environmentId)) {
        throw new EnvironmentCapabilityBusyException(
            "environment " + environmentId + " is being updated");
      }
      invocationTracker.register(active);

      Envelope invokeEnvelope = invokeEnvelope(environmentId, invocationId, request, settings());
      try {
        outcome = session.offerResult(invokeEnvelope);
        if (outcome == DaemonOfferResult.ACCEPTED) {
          active.deliveredGeneration = session.generation();
        }
      } catch (RuntimeException error) {
        outcome = DaemonOfferResult.CLOSED;
        sendFailure = error;
      }
    }
    if (sendFailure != null) {
      session.close();
      return active;
    }
    if (outcome == DaemonOfferResult.ACCEPTED) {
      return active;
    }
    invocationTracker.unregister(active);
    active.markTerminal();
    if (outcome == DaemonOfferResult.BUSY) {
      throw new EnvironmentCapabilityBusyException(
          "daemon outbound queue is full for environment " + environmentId);
    }
    throw unavailable(environmentId, descriptor.id().value());
  }

  /** 组装 INVOKE：能力参数原样透传，输出上限与图片档位取自当前 settings。 */
  private static Envelope invokeEnvelope(
      EnvironmentId environmentId,
      UUID invocationId,
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentServerSettings currentSettings) {
    Invoke.Builder invoke =
        Invoke.newBuilder()
            .setCapabilityId(request.descriptor().id().value())
            .setArgumentsJson(request.call().argumentsJson())
            .setTimeoutMillis(request.timeout().toMillis())
            .setMaxOutputBytes(currentSettings.maxOutputBytes())
            .setImageInputTier(currentSettings.imageInputTier());
    if (request.expectedFile() != null) {
      ExpectedFile.Builder expectedFile =
          ExpectedFile.newBuilder().setPath(request.expectedFile().path());
      if (request.expectedFile().sha256() != null) {
        expectedFile.setSha256(request.expectedFile().sha256());
      }
      invoke.setExpectedFile(expectedFile.build());
    }
    return Envelope.newBuilder()
        .setMessageType(MessageType.INVOKE)
        .setEnvironmentId(environmentId.value().toString())
        .setInvocationId(invocationId.toString())
        .setInvoke(invoke.build())
        .build();
  }

  @Override
  public EnvironmentCapabilityExecutionHandle attach(
      EnvironmentId environmentId,
      UUID invocationId,
      EnvironmentCapabilityExecutionListener listener) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(listener, "listener");

    if (draining) {
      throw unavailable(environmentId, "invocation " + invocationId);
    }
    DaemonConnectionSession session =
        requireReadySession(environmentId, "invocation " + invocationId);
    UUID leaseToken = session.leaseToken();
    requireHeldLease(environmentId, leaseToken, "invocation " + invocationId);

    ActiveInvocation active = newActiveInvocation(environmentId, invocationId, listener, session);

    DaemonOfferResult outcome = null;
    RuntimeException sendFailure = null;
    synchronized (session.stateLock()) {
      if (!session.isReady() || !leaseToken.equals(session.leaseToken())) {
        throw unavailable(environmentId, "invocation " + invocationId);
      }
      invocationTracker.registerAttach(active);

      Envelope recoverEnvelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.RECOVER)
              .setEnvironmentId(environmentId.value().toString())
              .setInvocationId(invocationId.toString())
              .setRecover(Recover.getDefaultInstance())
              .build();
      try {
        outcome = session.offerResult(recoverEnvelope);
        if (outcome == DaemonOfferResult.ACCEPTED) {
          active.deliveredGeneration = session.generation();
        }
      } catch (RuntimeException error) {
        outcome = DaemonOfferResult.CLOSED;
        sendFailure = error;
      }
    }

    if (sendFailure != null
        || outcome == DaemonOfferResult.CLOSED
        || outcome == DaemonOfferResult.BUSY) {
      session.close();
      return active;
    }
    return active;
  }

  public void drain() {
    draining = true;
    List<DaemonConnectionSession> activeSessions;
    synchronized (inventory) {
      activeSessions = new ArrayList<>(sessions.values());
    }
    for (DaemonConnectionSession session : activeSessions) {
      try {
        session.drain();
      } catch (RuntimeException error) {
        DaemonFailures.log(EnvironmentDaemonServer.class, "control drain", error);
      }
      UUID leaseToken = session.leaseToken();
      if (leaseToken != null) {
        try {
          leaseStore.release(session.environmentId(), leaseToken);
        } catch (RuntimeException error) {
          DaemonFailures.log(EnvironmentDaemonServer.class, "lease release", error);
        }
      }
    }
  }

  public void expire(EnvironmentCapabilityExecutionHandle handle) {
    Objects.requireNonNull(handle, "handle");
    if (!(handle instanceof ActiveInvocation active)) {
      throw new IllegalArgumentException("execution handle is not owned by this server");
    }
    synchronized (active) {
      active.cancelled = true;
    }
    if (!invocationTracker.retire(active)) {
      return;
    }
    DaemonConnectionSession session = sessionOf(active.environmentId);
    if (!draining && session != null) {
      active.deliverCancel(session);
    }
    transferCoordinator.releaseAllTransfers(active, ticketService);
  }

  public boolean isReady(EnvironmentId environmentId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    return session != null && session.isReady();
  }

  public boolean holdsReadyLease(EnvironmentId environmentId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null) {
      return false;
    }
    UUID leaseToken = session.leaseToken();
    try {
      return leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      return false;
    }
  }

  public Optional<DaemonRuntimeSnapshot> runtimeStatus(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null) {
      return Optional.empty();
    }
    UUID leaseToken = session.leaseToken();
    try {
      if (!leaseStore.hasActiveLeaseToken(environmentId, leaseToken)) {
        return Optional.empty();
      }
    } catch (RuntimeException error) {
      return Optional.empty();
    }
    return session.runtimeStatus();
  }

  public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    return session != null && session.isReady()
        ? Optional.of(session.identity())
        : Optional.empty();
  }

  public void beginUpdate(EnvironmentId environmentId, String operationId) {
    // 与 invoke 一致：会话缺失或未就绪都收敛为 capability 不可用，而不是把 null 会话泄漏成运行时异常。
    DaemonConnectionSession session = requireReadySession(environmentId, "update");
    // 与 invoke 使用同一把会话锁：计数与更新窗口注册对 register/检查必须原子，否则调用可在两者之间登记。
    synchronized (session.stateLock()) {
      int inFlight = invocationTracker.activeInvocationCount(environmentId);
      updateCoordinator.beginUpdate(environmentId, operationId, session, inFlight);
    }
  }

  public void endUpdate(EnvironmentId environmentId, String operationId) {
    updateCoordinator.endUpdate(environmentId, operationId);
  }

  public boolean isUpdating(EnvironmentId environmentId) {
    return updateCoordinator.isUpdating(environmentId);
  }

  public int activeInvocationCount(EnvironmentId environmentId) {
    return invocationTracker.activeInvocationCount(environmentId);
  }

  public void sendUpdate(EnvironmentId environmentId, DaemonUpdateCommand command) {
    DaemonConnectionSession session = sessionOf(environmentId);
    updateCoordinator.sendUpdate(environmentId, session, command);
  }

  /** 对指定 Environment 发起一次受管 IDE 请求。 */
  public DaemonIdeCall requestIde(EnvironmentId environmentId, IdeCommand command) {
    if (updateCoordinator.isUpdating(environmentId)) {
      throw new EnvironmentCapabilityBusyException(
          "environment " + environmentId + " is being updated");
    }
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null) {
      throw unavailable(environmentId, "ide");
    }
    return ideCoordinator.requestIde(environmentId, session, command, leaseStore);
  }

  public void abandonIde(UUID invocationId) {
    ideCoordinator.abandonIde(invocationId);
  }

  public void sendIdeLease(
      EnvironmentId environmentId, DaemonConnectionIdentity expectedIdentity, IdeLease ideLease) {
    if (updateCoordinator.isUpdating(environmentId)) {
      throw new EnvironmentCapabilityBusyException(
          "environment " + environmentId + " is being updated");
    }
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null) {
      throw new EnvironmentCapabilityUnavailableException(
          environmentId + " is not ready for IDE_LEASE");
    }
    ideCoordinator.sendIdeLease(environmentId, session, expectedIdentity, ideLease, leaseStore);
  }

  public int activeIdeCount() {
    return ideCoordinator.activeIdeCount();
  }

  private DaemonConnectionSession sessionOf(EnvironmentId environmentId) {
    synchronized (inventory) {
      return sessions.get(environmentId);
    }
  }

  private void removeSession(DaemonConnectionSession session) {
    synchronized (inventory) {
      sessions.remove(session.environmentId(), session);
    }
  }

  private String replaceDaemonInstance(EnvironmentId environmentId, String daemonInstanceId) {
    synchronized (inventory) {
      String previous = daemonInstances.put(environmentId, daemonInstanceId);
      return previous == null || previous.equals(daemonInstanceId) ? null : previous;
    }
  }

  private EnvironmentServerSettings settings() {
    return Objects.requireNonNull(settingsSupplier.get(), "settings");
  }

  private static void validateRequiredWorkdirShape(
      DaemonOperatingSystem operatingSystem,
      EnvironmentCapabilityDescriptor descriptor,
      EnvironmentCapabilityCall call) {
    if (!descriptor.inputSchema().required().contains("workdir")) {
      return;
    }
    JsonNode arguments = JsonValues.readTree(call.argumentsJson());
    JsonNode workdir = arguments.get("workdir");
    try {
      DaemonWorkdirSyntax.requireAbsolute(
          workdir == null || !workdir.isString() ? null : workdir.stringValue(), operatingSystem);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "invalid workdir for capability " + descriptor.id().value() + ": " + error.getMessage(),
          error);
    }
  }

  private static EnvironmentCapabilityUnavailableException unavailable(
      EnvironmentId environmentId, String capabilityId) {
    return new EnvironmentCapabilityUnavailableException(
        environmentId + " is unavailable; " + capabilityId + " cannot be executed");
  }
}
