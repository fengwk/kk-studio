package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonHostInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.Cancel;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.EnvironmentConfig;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.Ready;
import fun.fengwk.kkstudio.platform.daemon.protocol.Recover;
import fun.fengwk.kkstudio.platform.daemon.protocol.TemporaryResourcePolicy;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 单个 Daemon 控制连接的会话状态机：实现 {@link DaemonSession}，负责入站信封分派、gate 串行化与锁外回调保序。 */
final class DaemonConnectionSession implements DaemonSession {

  private static final Logger LOG = System.getLogger(DaemonConnectionSession.class.getName());

  private final Object gate = new Object();
  private final Object stateLock = new Object();
  private final DaemonCallbackQueue callbacks = new DaemonCallbackQueue();

  private final long generation;
  private final EnvironmentId environmentId;
  private final String daemonInstanceId;
  private final UUID leaseToken;
  private final DaemonControlChannel control;
  private final EnvironmentTunnel tunnel;
  private final DaemonLeaseStore leaseStore;
  private final EnvironmentConfigSource configSource;
  private final EnvironmentSessionListener sessionListener;
  private final EnvironmentUpdateListener updateListener;
  private final DaemonResourceTicketService ticketService;
  private final Supplier<EnvironmentServerSettings> settingsSupplier;
  private final DaemonInvocationTracker invocationTracker;
  private final DaemonTransferCoordinator transferCoordinator;
  private final DaemonDownloadCoordinator downloadCoordinator = new DaemonDownloadCoordinator();
  private final DaemonIdeCoordinator ideCoordinator;
  private final DaemonUpdateCoordinator updateCoordinator;
  private final Consumer<DaemonConnectionSession> onClosed;

  private boolean ready;
  private boolean cleaned;
  private boolean closeAfterFlush;
  private String daemonVersion;
  private DaemonOperatingSystem daemonOperatingSystem;
  private final DaemonRuntimeStatusMerger runtimeStatus = new DaemonRuntimeStatusMerger();
  private long temporaryResourceTtlSeconds;
  private long temporaryResourceCleanupIntervalSeconds;
  private long lastSentVersion = -1L;

  DaemonConnectionSession(
      long generation,
      EnvironmentId environmentId,
      String daemonInstanceId,
      UUID leaseToken,
      DaemonControlChannel control,
      EnvironmentTunnel tunnel,
      DaemonLeaseStore leaseStore,
      EnvironmentConfigSource configSource,
      EnvironmentSessionListener sessionListener,
      EnvironmentUpdateListener updateListener,
      DaemonResourceTicketService ticketService,
      Supplier<EnvironmentServerSettings> settingsSupplier,
      DaemonInvocationTracker invocationTracker,
      DaemonTransferCoordinator transferCoordinator,
      DaemonIdeCoordinator ideCoordinator,
      DaemonUpdateCoordinator updateCoordinator,
      Consumer<DaemonConnectionSession> onClosed) {
    this.generation = generation;
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
    this.control = Objects.requireNonNull(control, "control");
    this.tunnel = tunnel;
    this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    this.configSource = Objects.requireNonNull(configSource, "configSource");
    this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener");
    this.updateListener = Objects.requireNonNull(updateListener, "updateListener");
    this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
    this.invocationTracker = Objects.requireNonNull(invocationTracker, "invocationTracker");
    this.transferCoordinator = Objects.requireNonNull(transferCoordinator, "transferCoordinator");
    this.ideCoordinator = Objects.requireNonNull(ideCoordinator, "ideCoordinator");
    this.updateCoordinator = Objects.requireNonNull(updateCoordinator, "updateCoordinator");
    this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
  }

  long generation() {
    return generation;
  }

  EnvironmentId environmentId() {
    return environmentId;
  }

  String daemonInstanceId() {
    return daemonInstanceId;
  }

  UUID leaseToken() {
    synchronized (stateLock) {
      return leaseToken;
    }
  }

  boolean isReady() {
    synchronized (stateLock) {
      return ready && !cleaned;
    }
  }

  DaemonOperatingSystem daemonOperatingSystem() {
    synchronized (stateLock) {
      return daemonOperatingSystem;
    }
  }

  Optional<DaemonRuntimeSnapshot> runtimeStatus() {
    synchronized (stateLock) {
      return Optional.ofNullable(runtimeStatus.current());
    }
  }

  DaemonConnectionIdentity identity() {
    return new DaemonConnectionIdentity(environmentId, daemonInstanceId);
  }

  EnvironmentTunnel tunnel() {
    return tunnel;
  }

  Object stateLock() {
    return stateLock;
  }

  void drain() {
    control.drain();
  }

  DaemonOfferResult offerResult(Envelope envelope) {
    DaemonOfferResult outcome;
    synchronized (stateLock) {
      if (cleaned) {
        return DaemonOfferResult.CLOSED;
      }
      outcome = control.offer(envelope);
    }
    if (envelope.getMessageType() == MessageType.CANCEL
        && (outcome == DaemonOfferResult.BUSY || outcome == DaemonOfferResult.CLOSED)) {
      close();
    }
    return outcome;
  }

  boolean offer(Envelope envelope) {
    try {
      return offerResult(envelope) == DaemonOfferResult.ACCEPTED;
    } catch (RuntimeException error) {
      return false;
    }
  }

  void initializeSettings(EnvironmentServerSettings initial) {
    this.temporaryResourceTtlSeconds = initial.temporaryResourceTtlSeconds();
    this.temporaryResourceCleanupIntervalSeconds =
        initial.temporaryResourceCleanupIntervalSeconds();
  }

  /** 已连接 Daemon 收到系统设置变更后重发当前 SETTINGS。 */
  boolean refreshSettings() {
    EnvironmentServerSettings current = settings();
    Envelope envelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.SETTINGS)
            .setEnvironmentId(environmentId.value().toString())
            .setSettings(EnvironmentDaemonServer.settingsMessage(current))
            .build();
    synchronized (stateLock) {
      if (!offer(envelope)) {
        return false;
      }
      temporaryResourceTtlSeconds = current.temporaryResourceTtlSeconds();
      temporaryResourceCleanupIntervalSeconds = current.temporaryResourceCleanupIntervalSeconds();
      return true;
    }
  }

  /** 初始控制流：SETTINGS 之后立即下发当前完整卡片配置；缺失卡片明确报错并关闭。 */
  void sendInitialEnvironmentConfig() {
    Optional<EnvironmentConfigSnapshot> snapshot;
    try {
      snapshot = configSource.find(environmentId);
    } catch (RuntimeException error) {
      DaemonFailures.log(
          DaemonConnectionSession.class,
          "initial environment config lookup for " + environmentId,
          error);
      return;
    }
    if (snapshot.isEmpty()) {
      failMissingEnvironmentConfig();
      return;
    }
    reconcileEnvironmentConfig(snapshot.get());
  }

  /** 心跳或通知触发的权威配置回读；查询失败保留当前连接，由下一次心跳/通知继续对账。 */
  void reconcileEnvironmentConfig() {
    Optional<EnvironmentConfigSnapshot> snapshot;
    try {
      snapshot = configSource.find(environmentId);
    } catch (RuntimeException error) {
      DaemonFailures.log(
          DaemonConnectionSession.class, "environment config lookup for " + environmentId, error);
      return;
    }
    if (snapshot.isEmpty()) {
      failMissingEnvironmentConfig();
      return;
    }
    reconcileEnvironmentConfig(snapshot.get());
  }

  boolean reconcileEnvironmentConfig(EnvironmentConfigSnapshot snapshot) {
    synchronized (stateLock) {
      if (cleaned || snapshot.version() <= lastSentVersion) {
        return !cleaned;
      }
      Envelope update =
          Envelope.newBuilder()
              .setMessageType(MessageType.ENVIRONMENT_CONFIG)
              .setEnvironmentId(environmentId.value().toString())
              .setEnvironmentConfig(
                  EnvironmentConfig.newBuilder()
                      .setVersion(snapshot.version())
                      .setConfig(snapshot.config())
                      .build())
              .build();
      DaemonOfferResult result;
      try {
        result = control.offer(update);
      } catch (RuntimeException error) {
        result = DaemonOfferResult.CLOSED;
      }
      if (result == DaemonOfferResult.ACCEPTED) {
        lastSentVersion = snapshot.version();
        return true;
      }
    }
    close();
    return false;
  }

  private void failMissingEnvironmentConfig() {
    Envelope.Builder builder =
        Envelope.newBuilder()
            .setMessageType(MessageType.ERROR)
            .setEnvironmentId(environmentId.value().toString());
    builder.getErrorBuilder().setMessage("environment configuration not found");
    try {
      if (control.offer(builder.build()) == DaemonOfferResult.ACCEPTED) {
        synchronized (stateLock) {
          closeAfterFlush = true;
        }
      }
    } catch (RuntimeException error) {
      DaemonFailures.log(
          DaemonConnectionSession.class,
          "missing environment configuration notification for " + environmentId,
          error);
    }
    close();
  }

  @Override
  public void receive(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    DaemonTransferCoordinator.PreparedTransfer prepared = null;
    DaemonDownloadCoordinator.PreparedDownload preparedDownload = null;
    List<Runnable> deferred = new ArrayList<>();
    RuntimeException protocolError = null;
    boolean backlogFull = false;

    synchronized (gate) {
      if (callbacks.full()) {
        backlogFull = true;
      } else {
        try {
          if (envelope.hasEnvironmentId()
              && !environmentId.value().toString().equals(envelope.getEnvironmentId())) {
            throw new ControlProtocolException(
                "envelope environmentId does not match bound connection: "
                    + envelope.getEnvironmentId());
          }
          switch (envelope.getMessageType()) {
            case READY -> handleReady(envelope, deferred);
            case HEARTBEAT -> handleHeartbeat(envelope, deferred);
            case RUNTIME_STATUS -> handleRuntimeStatus(envelope);
            case STARTED -> handleStarted(envelope);
            case PROGRESS -> handleProgress(envelope, deferred);
            case COMPLETED -> handleCompleted(envelope, deferred);
            case FAILED -> handleFailed(envelope, deferred);
            case CANCELLED -> handleCancelled(envelope, deferred);
            case EXECUTION_EXITED -> handleExecutionExited(envelope, deferred);
            case RECOVER_NOT_FOUND -> handleRecoverNotFound(envelope, deferred);
            case RESOURCE_UPLOAD_REQUEST -> prepared = prepareUploadReserve(envelope);
            case RESOURCE_UPLOAD_COMMIT -> prepared = prepareUploadCommit(envelope);
            case RESOURCE_DOWNLOAD_REQUEST -> preparedDownload = prepareDownload(envelope);
            case UPDATE_RESULT -> {
              requireReady();
              updateCoordinator.handleUpdateResult(
                  environmentId, envelope.getUpdateResult(), updateListener, deferred);
            }
            case IDE_RESULT -> {
              requireReady();
              UUID invocationId = parseInvocationId(envelope);
              ideCoordinator.handleIdeResult(invocationId, envelope.getIdeResult(), this, deferred);
            }
            case ERROR -> handleError(envelope);
            default -> throw new ControlProtocolException(
                "daemon must not send " + envelope.getMessageType() + " to server");
          }
        } catch (RuntimeException error) {
          protocolError = error;
        }
        if (protocolError == null) {
          callbacks.enqueue(deferred);
        }
      }
    }

    if (backlogFull) {
      failCallbackBacklog();
      return;
    }
    if (protocolError != null) {
      handleProtocolError(protocolError);
      return;
    }
    drainCallbacks();
    executePrepared(prepared, preparedDownload);
  }

  /** 锁外执行已准备好的传输：传输本身会做网络与存储 IO，不能占用接收锁。 */
  private void executePrepared(
      DaemonTransferCoordinator.PreparedTransfer prepared,
      DaemonDownloadCoordinator.PreparedDownload preparedDownload) {
    if (prepared != null) {
      transferCoordinator.executeTransfer(prepared, ticketService, invocationTracker);
    }
    if (preparedDownload != null) {
      downloadCoordinator.executeDownload(preparedDownload, ticketService, invocationTracker);
    }
  }

  /** 上传申请：invocation 必须属于本连接，额度按当前 settings 计算。 */
  private DaemonTransferCoordinator.PreparedTransfer prepareUploadReserve(Envelope envelope) {
    requireReady();
    ActiveInvocation active = requireRegisteredInvocation(envelope, "resource upload control");
    return transferCoordinator.prepareUploadReserve(
        this, active, envelope.getResourceUploadRequest(), settings().maxOutputBytes());
  }

  /** 上传提交：与申请同理，只接受本连接已登记的 invocation。 */
  private DaemonTransferCoordinator.PreparedTransfer prepareUploadCommit(Envelope envelope) {
    requireReady();
    ActiveInvocation active = requireRegisteredInvocation(envelope, "resource upload control");
    return transferCoordinator.prepareUploadCommit(
        this, active, envelope.getResourceUploadCommit());
  }

  /** 下载申请：归档由服务端冻结记录决定，因此只校验 invocation 归属。 */
  private DaemonDownloadCoordinator.PreparedDownload prepareDownload(Envelope envelope) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.registeredInvocation(environmentId, invocationId);
    return downloadCoordinator.prepare(
        this, active, invocationId.toString(), envelope.getResourceDownloadRequest());
  }

  /** 控制消息必须由本连接已登记且仍活跃的 invocation 发出。 */
  private ActiveInvocation requireRegisteredInvocation(Envelope envelope, String purpose) {
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.registeredInvocation(environmentId, invocationId);
    if (active == null) {
      throw new ControlProtocolException(
          purpose + " does not own invocationId: " + envelope.getInvocationId());
    }
    return active;
  }

  private void handleReady(Envelope envelope, List<Runnable> deferred) {
    synchronized (stateLock) {
      if (ready) {
        throw new ControlProtocolException("READY may only be sent once per connection");
      }
      if (cleaned) {
        throw new ControlProtocolException("route fence lost for environment " + environmentId);
      }
    }
    Ready readyMessage = envelope.getReady();
    DaemonHostInfo hostInfo = DaemonProtoMapping.toDomainHostInfo(readyMessage.getHostInfo());
    DaemonReadyMetadata readyMetadata =
        new DaemonReadyMetadata(readyMessage.getDaemonVersion(), hostInfo);
    boolean ok = leaseStore.markReady(environmentId, leaseToken, readyMetadata, timeout());
    if (!ok) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    Optional<EnvironmentConfigSnapshot> latestConfig = lookupConfigOnReady();
    DaemonRuntimeSnapshot readySnapshot =
        DaemonProtoMapping.toDomainSnapshot(readyMessage.getRuntimeStatus());
    synchronized (stateLock) {
      if (cleaned) {
        throw new ControlProtocolException("route fence lost for environment " + environmentId);
      }
      this.ready = true;
      this.daemonVersion = readyMessage.getDaemonVersion();
      this.daemonOperatingSystem = hostInfo.operatingSystem();
      runtimeStatus.merge(readySnapshot, daemonVersion);
    }
    if (latestConfig != null) {
      reconcileEnvironmentConfig(latestConfig.get());
    }
    deferred.add(
        () -> {
          try {
            sessionListener.onEnvironmentReady(environmentId);
          } catch (RuntimeException error) {
            DaemonFailures.log(
                DaemonConnectionSession.class, "environment ready notification", error);
          }
        });
    recoverActiveInvocations();
    resendPendingExitConfirmations();
  }

  /**
   * READY 提交后回读可选的最新配置。
   *
   * <p>回读失败不否定 READY：下一次心跳或配置通知会继续对账。返回 {@code null} 表示“回读失败，无需立即应用”；返回空 Optional 表示
   * “已确认不存在”，由调用方按缺失配置处理。
   */
  private Optional<EnvironmentConfigSnapshot> lookupConfigOnReady() {
    Optional<EnvironmentConfigSnapshot> latest;
    try {
      latest = configSource.find(environmentId);
    } catch (RuntimeException error) {
      DaemonFailures.log(
          DaemonConnectionSession.class,
          "ready environment config lookup for " + environmentId,
          error);
      return null;
    }
    if (latest.isEmpty()) {
      throw new ControlProtocolException(
          "environment configuration not found for " + environmentId);
    }
    return latest;
  }

  private void handleHeartbeat(Envelope envelope, List<Runnable> deferred) {
    requireBound();
    boolean ok = leaseStore.heartbeat(environmentId, leaseToken, timeout());
    if (!ok) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    DaemonRuntimeSnapshot snapshot =
        DaemonProtoMapping.toDomainSnapshot(envelope.getHeartbeat().getRuntimeStatus());
    boolean statusChanged = applyRuntimeStatus(snapshot);
    boolean currentlyReady = isReady();
    if (currentlyReady) {
      reconcileTemporaryResourcePolicy();
      DaemonConnectionIdentity identity = identity();
      Long ideLeaseRound =
          envelope.getHeartbeat().hasIdeLeaseRound()
              ? envelope.getHeartbeat().getIdeLeaseRound()
              : null;
      deferred.add(() -> sessionListener.onHeartbeat(environmentId, identity, ideLeaseRound));
    }
    if (statusChanged && currentlyReady) {
      publishRuntimeStatusChanged();
    }
    reconcileEnvironmentConfig();
  }

  private void handleRuntimeStatus(Envelope envelope) {
    requireBound();
    boolean holdsLease;
    try {
      holdsLease =
          isReady()
              ? leaseStore.holdsReadyLease(environmentId, leaseToken)
              : leaseStore.hasActiveLeaseToken(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new ControlProtocolException("database unavailable: " + error.getMessage(), error);
    }
    if (!holdsLease) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    boolean statusChanged =
        applyRuntimeStatus(
            DaemonProtoMapping.toDomainSnapshot(envelope.getRuntimeStatus().getRuntimeStatus()));
    if (statusChanged && isReady()) {
      publishRuntimeStatusChanged();
    }
  }

  /** 已结束或从未登记的 invocation 会继续收到迟到回调；墓碑表示该调用已正常收尾，因此只有完全未知才值得排查。 */
  private void logUnknown(String messageType, UUID invocationId) {
    if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
      LOG.log(
          Level.DEBUG,
          "ignoring {0} for unknown invocation {1} on {2}",
          messageType,
          invocationId,
          environmentId);
    }
  }

  private void handleStarted(Envelope envelope) {
    requireReady();
    if (envelope.getStarted().hasReplayed() && !envelope.getStarted().getReplayed()) {
      throw new ControlProtocolException("started.replayed must be true when present");
    }
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.callbackTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("STARTED", invocationId);
    }
  }

  private void handleProgress(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.callbackTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("PROGRESS", invocationId);
      return;
    }
    EnvironmentCapabilityResult result =
        DaemonProtoMapping.toDomainResult(envelope.getProgress().getResult());
    deferred.add(
        () -> {
          if (invocationTracker.isActive(active)) {
            active.listener.onPartial(result);
          }
        });
  }

  private void handleCompleted(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("COMPLETED", invocationId);
      return;
    }
    EnvironmentCapabilityResult result;
    Set<UUID> referenced;
    try {
      result = DaemonProtoMapping.toDomainResult(envelope.getCompleted().getResult());
      referenced = transferCoordinator.requireCompletedUploads(active, result);
    } catch (RuntimeException error) {
      // 调用已迁移到墓碑，后续 expire 不再收尾它；终态本身违规时上传仍须在本路径释放。
      transferCoordinator.releaseAllTransfers(active, ticketService);
      throw error;
    }
    deferred.add(() -> active.listener.onComplete(result));
    deferred.add(
        () -> transferCoordinator.releaseUnreferencedTransfers(active, referenced, ticketService));
  }

  private void handleFailed(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("FAILED", invocationId);
      return;
    }
    String message = envelope.getFailed().getMessage();
    deferred.add(() -> active.listener.onError(new EnvironmentCapabilityFailedException(message)));
    deferred.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
  }

  private void handleCancelled(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("CANCELLED", invocationId);
      return;
    }
    String reason = envelope.getCancelled().getReason();
    deferred.add(
        () -> active.listener.onError(new EnvironmentCapabilityCancelledException(reason)));
    deferred.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
  }

  private void handleRecoverNotFound(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      logUnknown("RECOVER_NOT_FOUND", invocationId);
      return;
    }
    deferred.add(
        () ->
            active.listener.onError(
                new EnvironmentCapabilitySendUncertainException(
                    "invocation was not found on daemon; capability invocation outcome is uncertain.")));
    deferred.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
  }

  private void handleExecutionExited(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = parseInvocationId(envelope);
    Optional<ExitConfirmation> confirmation =
        invocationTracker.claimExitConfirmation(environmentId, invocationId, daemonInstanceId);
    boolean known =
        confirmation.isPresent() || invocationTracker.isKnown(environmentId, invocationId);
    if (confirmation.isPresent()) {
      EnvironmentCapabilityExecutionListener listener = confirmation.get().listener;
      deferred.add(
          () -> {
            try {
              listener.onExitConfirmed();
            } catch (RuntimeException failure) {
              DaemonFailures.log(
                  DaemonConnectionSession.class, "execution exit confirmation", failure);
            }
          });
      return;
    }
    if (!known) {
      LOG.log(
          Level.DEBUG,
          "ignoring EXECUTION_EXITED for unknown invocation {0} on {1}",
          invocationId,
          environmentId);
    }
  }

  private void handleError(Envelope envelope) {
    LOG.log(
        Level.WARNING, "received error envelope from daemon: " + envelope.getError().getMessage());
  }

  private boolean applyRuntimeStatus(DaemonRuntimeSnapshot snapshot) {
    synchronized (stateLock) {
      if (cleaned) {
        throw new ControlProtocolException("runtimeStatus arrived after connection closed");
      }
      return runtimeStatus.merge(snapshot, ready ? daemonVersion : null);
    }
  }

  private void publishRuntimeStatusChanged() {
    // 快照先可读，再发布；围栏失效或发布失败均交给会话的 fail-closed 路径处理。
    if (!leaseStore.runtimeStatusChanged(environmentId, leaseToken)) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
  }

  private void reconcileTemporaryResourcePolicy() {
    EnvironmentServerSettings current = settings();
    long ttlSeconds = current.temporaryResourceTtlSeconds();
    long cleanupIntervalSeconds = current.temporaryResourceCleanupIntervalSeconds();
    if (temporaryResourceTtlSeconds == ttlSeconds
        && temporaryResourceCleanupIntervalSeconds == cleanupIntervalSeconds) {
      return;
    }
    Envelope policyEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.TEMPORARY_RESOURCE_POLICY)
            .setEnvironmentId(environmentId.value().toString())
            .setTemporaryResourcePolicy(
                TemporaryResourcePolicy.newBuilder()
                    .setTemporaryResourceTtlSeconds(ttlSeconds)
                    .setTemporaryResourceCleanupIntervalSeconds(cleanupIntervalSeconds)
                    .build())
            .build();
    if (offer(policyEnvelope)) {
      temporaryResourceTtlSeconds = ttlSeconds;
      temporaryResourceCleanupIntervalSeconds = cleanupIntervalSeconds;
    }
  }

  private void recoverActiveInvocations() {
    for (ActiveInvocation active : invocationTracker.activeInvocations(environmentId)) {
      DaemonOfferResult outcome;
      synchronized (stateLock) {
        if (!ready || cleaned) {
          return;
        }
        if (active.isTerminal()
            || (!active.isCancelled() && active.deliveredGeneration == generation)
            || active.cancelDeliveredGeneration == generation) {
          continue;
        }
        boolean recover = !active.isCancelled();
        Envelope.Builder envelope =
            Envelope.newBuilder()
                .setMessageType(recover ? MessageType.RECOVER : MessageType.CANCEL)
                .setEnvironmentId(environmentId.value().toString())
                .setInvocationId(active.invocationId.toString());
        if (recover) {
          envelope.setRecover(Recover.getDefaultInstance());
        } else {
          envelope.setCancel(Cancel.getDefaultInstance());
        }
        try {
          outcome = control.offer(envelope.build());
        } catch (RuntimeException transportFailure) {
          outcome = null;
        }
        if (outcome == DaemonOfferResult.ACCEPTED) {
          if (recover) {
            active.deliveredGeneration = generation;
          } else {
            active.noteCancelDelivered(generation);
          }
        }
      }
      if (outcome == null
          || outcome == DaemonOfferResult.CLOSED
          || outcome == DaemonOfferResult.BUSY) {
        close();
        return;
      }
    }
  }

  private void resendPendingExitConfirmations() {
    List<UUID> pending =
        invocationTracker.pendingExitConfirmations(environmentId, daemonInstanceId);
    for (UUID invocationId : pending) {
      DaemonOfferResult outcome;
      synchronized (stateLock) {
        if (!ready || cleaned) {
          return;
        }
        Envelope envelope =
            Envelope.newBuilder()
                .setMessageType(MessageType.CANCEL)
                .setEnvironmentId(environmentId.value().toString())
                .setInvocationId(invocationId.toString())
                .setCancel(Cancel.getDefaultInstance())
                .build();
        try {
          outcome = control.offer(envelope);
        } catch (RuntimeException transportFailure) {
          outcome = null;
        }
      }
      if (outcome == null
          || outcome == DaemonOfferResult.CLOSED
          || outcome == DaemonOfferResult.BUSY) {
        close();
        return;
      }
    }
  }

  private void handleProtocolError(RuntimeException error) {
    String message =
        error.getMessage() == null ? "invalid daemon protocol message" : error.getMessage();
    Envelope.Builder builder =
        Envelope.newBuilder()
            .setMessageType(MessageType.ERROR)
            .setEnvironmentId(environmentId.value().toString());
    builder.getErrorBuilder().setMessage(message);
    Envelope errorEnvelope = builder.build();
    try {
      if (control.offer(errorEnvelope) == DaemonOfferResult.ACCEPTED) {
        synchronized (stateLock) {
          closeAfterFlush = true;
        }
      }
    } catch (RuntimeException notificationFailure) {
      DaemonFailures.log(
          DaemonConnectionSession.class,
          "protocol error notification for " + environmentId,
          notificationFailure);
    }
    close();
  }

  private void failCallbackBacklog() {
    List<ActiveInvocation> abandoned = invocationTracker.takeAbandonedInvocations(environmentId);
    if (!abandoned.isEmpty()) {
      List<Runnable> reclaim = new ArrayList<>();
      for (ActiveInvocation active : abandoned) {
        reclaim.add(
            () ->
                active.listener.onError(
                    new EnvironmentCapabilitySendUncertainException(
                        "daemon callback backlog for "
                            + environmentId
                            + " exceeded the per-connection bound; capability invocation outcome"
                            + " is uncertain.")));
        reclaim.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
      }
      callbacks.enqueue(reclaim);
    }
    close();
    drainCallbacks();
  }

  private void requireBound() {
    synchronized (stateLock) {
      if (cleaned) {
        throw new ControlProtocolException("daemon connection is already closed");
      }
    }
  }

  private void requireReady() {
    synchronized (stateLock) {
      if (!ready || cleaned) {
        throw new ControlProtocolException("READY must precede invocation callbacks");
      }
    }
  }

  private void drainCallbacks() {
    callbacks.drain(
        error ->
            DaemonFailures.log(
                DaemonConnectionSession.class, "callback for " + environmentId, error));
  }

  @Override
  public void close() {
    closed();
  }

  @Override
  public void closed() {
    boolean flush;
    synchronized (stateLock) {
      if (cleaned) {
        return;
      }
      cleaned = true;
      ready = false;
      flush = closeAfterFlush;
    }
    onClosed.accept(this);
    Throwable leaseFailure = null;
    try {
      leaseStore.disconnect(environmentId, leaseToken, timeout());
    } catch (Throwable error) {
      leaseFailure = error;
    }
    Throwable closeFailure = null;
    try {
      if (flush) {
        control.closeAfterFlush();
      } else {
        control.close();
      }
    } catch (Throwable error) {
      closeFailure = error;
    }
    ideCoordinator.failPendingIdeCalls(this);
    DaemonCleanup.of(
            "connection cleanup lease disconnect",
            leaseFailure,
            "connection cleanup transport close",
            closeFailure,
            DaemonConnectionSession::logCleanupFailure)
        .throwIfFailed();
  }

  private Duration timeout() {
    return settings().heartbeatTimeout();
  }

  private EnvironmentServerSettings settings() {
    return Objects.requireNonNull(settingsSupplier.get(), "settings");
  }

  private static Throwable logCleanupFailure(
      String stage, Throwable failure, Throwable firstDiagnosticFailure) {
    try {
      DaemonFailures.log(DaemonConnectionSession.class, stage, failure);
      return firstDiagnosticFailure;
    } catch (Throwable diagnosticFailure) {
      if (firstDiagnosticFailure == null) {
        return diagnosticFailure;
      }
      if (firstDiagnosticFailure != diagnosticFailure) {
        firstDiagnosticFailure.addSuppressed(diagnosticFailure);
      }
      return firstDiagnosticFailure;
    }
  }

  /** invocationId 的必填与 canonical UUID 校验由协议层负责；这里只转换为 UUID。 */
  private static UUID parseInvocationId(Envelope envelope) {
    return UUID.fromString(envelope.getInvocationId());
  }
}
