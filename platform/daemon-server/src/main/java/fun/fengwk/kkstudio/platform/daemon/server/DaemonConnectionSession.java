package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.protocol.Architecture;
import fun.fengwk.kkstudio.platform.daemon.protocol.Cancel;
import fun.fengwk.kkstudio.platform.daemon.protocol.CapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.Content;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.EnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.protocol.JsonContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.OperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.Ready;
import fun.fengwk.kkstudio.platform.daemon.protocol.Recover;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.TemporaryResourcePolicy;
import fun.fengwk.kkstudio.platform.daemon.protocol.TextContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.tunnel.EnvironmentTunnel;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayDeque;
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
  private static final int MAX_PENDING_CALLBACK_BATCHES = 64;

  private final Object gate = new Object();
  private final Object stateLock = new Object();
  private final Object networkSettingsLock = new Object();
  private final ArrayDeque<List<Runnable>> pendingCallbacks = new ArrayDeque<>();
  private boolean drainingCallbacks;

  private final long generation;
  private final EnvironmentId environmentId;
  private final String daemonInstanceId;
  private final UUID leaseToken;
  private final DaemonControlChannel control;
  private final EnvironmentTunnel tunnel;
  private final DaemonLeaseStore leaseStore;
  private final EnvironmentSessionListener sessionListener;
  private final EnvironmentUpdateListener updateListener;
  private final DaemonResourceTicketService ticketService;
  private final Supplier<EnvironmentServerSettings> settingsSupplier;
  private final DaemonInvocationTracker invocationTracker;
  private final DaemonTransferCoordinator transferCoordinator;
  private final DaemonIdeCoordinator ideCoordinator;
  private final DaemonUpdateCoordinator updateCoordinator;
  private final Consumer<DaemonConnectionSession> onClosed;

  private boolean ready;
  private boolean cleaned;
  private boolean closeAfterFlush;
  private String daemonVersion;
  private DaemonOperatingSystem daemonOperatingSystem;
  private DaemonRuntimeSnapshot runtimeStatus;
  private long temporaryResourceTtlSeconds;
  private long temporaryResourceCleanupIntervalSeconds;
  private String proxyUrl;
  private String noProxyHosts;
  private boolean networkSettingsFailed;

  DaemonConnectionSession(
      long generation,
      EnvironmentId environmentId,
      String daemonInstanceId,
      UUID leaseToken,
      DaemonControlChannel control,
      EnvironmentTunnel tunnel,
      DaemonLeaseStore leaseStore,
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
      return Optional.ofNullable(runtimeStatus);
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
      if (cleaned || leaseToken == null) {
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
    this.proxyUrl = initial.proxyUrl();
    this.noProxyHosts = initial.noProxyHosts();
  }

  boolean reconcileNetworkSettings() {
    boolean failed = false;
    synchronized (networkSettingsLock) {
      if (networkSettingsFailed || !isReady()) {
        return false;
      }
      EnvironmentServerSettings current = settingsSupplier.get();
      if (Objects.equals(proxyUrl, current.proxyUrl())
          && Objects.equals(noProxyHosts, current.noProxyHosts())) {
        return true;
      }
      Envelope update =
          Envelope.newBuilder()
              .setMessageType(MessageType.SETTINGS)
              .setEnvironmentId(environmentId.value().toString())
              .setSettings(EnvironmentDaemonServer.settingsMessage(current))
              .build();
      DaemonOfferResult result;
      try {
        // 不持有 stateLock：传输可同步回调 closed()，关闭路径可能访问服务端 inventory。
        result = control.offer(update);
      } catch (RuntimeException error) {
        result = DaemonOfferResult.CLOSED;
      }
      if (result == DaemonOfferResult.ACCEPTED) {
        proxyUrl = current.proxyUrl();
        noProxyHosts = current.noProxyHosts();
      } else {
        networkSettingsFailed = true;
        failed = true;
      }
    }
    if (failed) {
      close();
      return false;
    }
    return isReady();
  }

  @Override
  public void receive(Envelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    DaemonTransferCoordinator.PreparedTransfer prepared = null;
    List<Runnable> deferred = new ArrayList<>();
    RuntimeException protocolError = null;
    boolean backlogFull = false;

    synchronized (gate) {
      if (callbackBacklogFull()) {
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
            case RESOURCE_UPLOAD_REQUEST -> {
              requireReady();
              UUID invocationId = requireCanonicalInvocationId(envelope);
              ActiveInvocation active =
                  invocationTracker.registeredInvocation(environmentId, invocationId);
              if (active == null) {
                throw new ControlProtocolException(
                    "resource upload control does not own invocationId: "
                        + envelope.getInvocationId());
              }
              prepared =
                  transferCoordinator.prepareUploadReserve(
                      this,
                      active,
                      envelope.getResourceUploadRequest(),
                      settings().maxResourceBytes());
            }
            case RESOURCE_UPLOAD_COMMIT -> {
              requireReady();
              UUID invocationId = requireCanonicalInvocationId(envelope);
              ActiveInvocation active =
                  invocationTracker.registeredInvocation(environmentId, invocationId);
              if (active == null) {
                throw new ControlProtocolException(
                    "resource upload control does not own invocationId: "
                        + envelope.getInvocationId());
              }
              prepared =
                  transferCoordinator.prepareUploadCommit(
                      this, active, envelope.getResourceUploadCommit());
            }
            case UPDATE_RESULT -> {
              requireReady();
              updateCoordinator.handleUpdateResult(
                  environmentId, envelope.getUpdateResult(), updateListener, deferred);
            }
            case IDE_RESULT -> {
              requireReady();
              UUID invocationId = requireCanonicalInvocationId(envelope);
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
          enqueueCallbacks(deferred);
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
    if (prepared != null) {
      transferCoordinator.executeTransfer(prepared, ticketService, invocationTracker);
    }
  }

  private void handleReady(Envelope envelope, List<Runnable> deferred) {
    synchronized (stateLock) {
      if (ready) {
        throw new ControlProtocolException("READY may only be sent once per connection");
      }
    }
    Ready readyMessage = envelope.getReady();
    EnvironmentInfo info = readyMessage.getEnvironment();
    DaemonOperatingSystem os = toDomainOs(info.getOperatingSystem());
    String arch = toDomainArch(info.getArchitecture());
    DaemonEnvironmentInfo envInfo =
        new DaemonEnvironmentInfo(
            os,
            info.getTimeZone(),
            info.getUserName(),
            info.getHomeDirectory(),
            info.getNote(),
            info.getTempDirectory(),
            arch);
    DaemonCapabilities capabilities =
        new DaemonCapabilities(readyMessage.getDaemonVersion(), envInfo);
    boolean ok = leaseStore.markReady(environmentId, leaseToken, capabilities, timeout());
    if (!ok) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    synchronized (stateLock) {
      if (cleaned) {
        throw new ControlProtocolException("route fence lost for environment " + environmentId);
      }
      this.ready = true;
      this.daemonVersion = readyMessage.getDaemonVersion();
      this.daemonOperatingSystem = os;
      this.runtimeStatus = toDomainSnapshot(readyMessage.getRuntimeStatus());
    }
    if (!reconcileNetworkSettings()) {
      return;
    }
    deferred.add(
        () -> {
          try {
            sessionListener.onEnvironmentReady(environmentId);
          } catch (RuntimeException error) {
            logFailure("environment ready notification", error);
          }
        });
    recoverActiveInvocations();
    resendPendingExitConfirmations();
  }

  private void handleHeartbeat(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    boolean ok = leaseStore.heartbeat(environmentId, leaseToken, timeout());
    if (!ok) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    DaemonRuntimeSnapshot snapshot = toDomainSnapshot(envelope.getHeartbeat().getRuntimeStatus());
    applyRuntimeStatus(snapshot);
    reconcileTemporaryResourcePolicy();
    DaemonConnectionIdentity identity = identity();
    Long ideLeaseRound =
        envelope.getHeartbeat().hasIdeLeaseRound()
            ? envelope.getHeartbeat().getIdeLeaseRound()
            : null;
    deferred.add(() -> sessionListener.onHeartbeat(environmentId, identity, ideLeaseRound));
  }

  private void handleRuntimeStatus(Envelope envelope) {
    requireReady();
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new ControlProtocolException("database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw new ControlProtocolException("route fence lost for environment " + environmentId);
    }
    synchronized (stateLock) {
      if (!ready || cleaned) {
        throw new ControlProtocolException("route fence lost for environment " + environmentId);
      }
    }
    applyRuntimeStatus(toDomainSnapshot(envelope.getRuntimeStatus().getRuntimeStatus()));
  }

  private void handleStarted(Envelope envelope) {
    requireReady();
    if (envelope.getStarted().hasReplayed() && !envelope.getStarted().getReplayed()) {
      throw new ControlProtocolException("started.replayed must be true when present");
    }
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.callbackTarget(environmentId, invocationId);
    if (active == null && !invocationTracker.hasTombstone(environmentId, invocationId)) {
      LOG.log(
          Level.DEBUG,
          "ignoring STARTED for unknown invocation {0} on {1}",
          invocationId,
          environmentId);
    }
  }

  private void handleProgress(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.callbackTarget(environmentId, invocationId);
    if (active == null) {
      if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
        LOG.log(
            Level.DEBUG,
            "ignoring PROGRESS for unknown invocation {0} on {1}",
            invocationId,
            environmentId);
      }
      return;
    }
    EnvironmentCapabilityResult result = toDomainResult(envelope.getProgress().getResult());
    deferred.add(
        () -> {
          if (invocationTracker.isActive(active)) {
            active.listener.onPartial(result);
          }
        });
  }

  private void handleCompleted(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
        LOG.log(
            Level.DEBUG,
            "ignoring COMPLETED for unknown invocation {0} on {1}",
            invocationId,
            environmentId);
      }
      return;
    }
    EnvironmentCapabilityResult result = toDomainResult(envelope.getCompleted().getResult());
    Set<UUID> referenced = transferCoordinator.requireCompletedUploads(active, result);
    deferred.add(() -> active.listener.onComplete(result));
    deferred.add(
        () -> transferCoordinator.releaseUnreferencedTransfers(active, referenced, ticketService));
  }

  private void handleFailed(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
        LOG.log(
            Level.DEBUG,
            "ignoring FAILED for unknown invocation {0} on {1}",
            invocationId,
            environmentId);
      }
      return;
    }
    String message = envelope.getFailed().getMessage();
    deferred.add(() -> active.listener.onError(new EnvironmentCapabilityFailedException(message)));
    deferred.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
  }

  private void handleCancelled(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
        LOG.log(
            Level.DEBUG,
            "ignoring CANCELLED for unknown invocation {0} on {1}",
            invocationId,
            environmentId);
      }
      return;
    }
    String reason = envelope.getCancelled().getReason();
    deferred.add(
        () -> active.listener.onError(new EnvironmentCapabilityCancelledException(reason)));
    deferred.add(() -> transferCoordinator.releaseAllTransfers(active, ticketService));
  }

  private void handleRecoverNotFound(Envelope envelope, List<Runnable> deferred) {
    requireReady();
    UUID invocationId = requireCanonicalInvocationId(envelope);
    ActiveInvocation active = invocationTracker.takeTerminalTarget(environmentId, invocationId);
    if (active == null) {
      if (!invocationTracker.hasTombstone(environmentId, invocationId)) {
        LOG.log(
            Level.DEBUG,
            "ignoring RECOVER_NOT_FOUND for unknown invocation {0} on {1}",
            invocationId,
            environmentId);
      }
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
    UUID invocationId = requireCanonicalInvocationId(envelope);
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
            } catch (RuntimeException | Error failure) {
              logFailure("execution exit confirmation", failure);
              throw failure;
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

  private void applyRuntimeStatus(DaemonRuntimeSnapshot snapshot) {
    boolean changed = false;
    synchronized (stateLock) {
      if (!ready || cleaned) {
        throw new ControlProtocolException("runtimeStatus arrived after connection left READY");
      }
      if (!snapshot.status().daemonVersion().equals(daemonVersion)) {
        throw new ControlProtocolException(
            "runtimeStatus.daemonVersion does not match the READY daemonVersion");
      }
      DaemonRuntimeSnapshot current = this.runtimeStatus;
      if (current == null || snapshot.revision() > current.revision()) {
        changed = current == null || !snapshot.status().equals(current.status());
        this.runtimeStatus = snapshot;
      } else if (snapshot.revision() == current.revision() && !snapshot.equals(current)) {
        throw new ControlProtocolException(
            "runtimeStatus revision " + snapshot.revision() + " changed without advancing");
      }
    }
    // 快照先可读，再发布；围栏失效或发布失败均交给会话的 fail-closed 路径处理。
    if (changed && !leaseStore.runtimeStatusChanged(environmentId, leaseToken)) {
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
    } catch (RuntimeException ignored) {
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
      synchronized (gate) {
        enqueueCallbacks(reclaim);
      }
    }
    close();
    drainCallbacks();
  }

  private void requireReady() {
    synchronized (stateLock) {
      if (!ready || cleaned) {
        throw new ControlProtocolException("READY must precede invocation callbacks");
      }
    }
  }

  private boolean callbackBacklogFull() {
    return pendingCallbacks.size() >= MAX_PENDING_CALLBACK_BATCHES;
  }

  private void enqueueCallbacks(List<Runnable> callbacks) {
    if (!callbacks.isEmpty()) {
      pendingCallbacks.addLast(callbacks);
    }
  }

  private void drainCallbacks() {
    synchronized (gate) {
      if (drainingCallbacks) {
        return;
      }
      drainingCallbacks = true;
    }
    while (true) {
      List<Runnable> batch;
      synchronized (gate) {
        batch = pendingCallbacks.pollFirst();
        if (batch == null) {
          drainingCallbacks = false;
          return;
        }
      }
      for (Runnable callback : batch) {
        try {
          callback.run();
        } catch (Throwable ignored) {
        }
      }
    }
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
    Error cleanupError = recordCleanupFailure(null, leaseFailure);
    cleanupError = recordCleanupFailure(cleanupError, closeFailure);
    Throwable diagnosticFailure = null;
    if (leaseFailure != null) {
      diagnosticFailure =
          logCleanupFailure("connection cleanup lease disconnect", leaseFailure, null);
    }
    if (closeFailure != null) {
      diagnosticFailure =
          logCleanupFailure("connection cleanup transport close", closeFailure, diagnosticFailure);
    }
    if (cleanupError != null) {
      if (diagnosticFailure != null && cleanupError != diagnosticFailure) {
        cleanupError.addSuppressed(diagnosticFailure);
      }
      throw cleanupError;
    }
    if (diagnosticFailure != null) {
      rethrowUnchecked(diagnosticFailure);
    }
  }

  private Duration timeout() {
    return settings().heartbeatTimeout();
  }

  private EnvironmentServerSettings settings() {
    return Objects.requireNonNull(settingsSupplier.get(), "settings");
  }

  private static Error recordCleanupFailure(Error first, Throwable failure) {
    if (failure == null) {
      return first;
    }
    if (first == null) {
      return failure instanceof Error error ? error : null;
    }
    if (first != failure) {
      first.addSuppressed(failure);
    }
    return first;
  }

  private static Throwable logCleanupFailure(
      String stage, Throwable failure, Throwable firstDiagnosticFailure) {
    try {
      logFailure(stage, failure);
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

  private static void rethrowUnchecked(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    throw new IllegalStateException("cleanup diagnostic failure is not unchecked", failure);
  }

  static void logFailure(String stage, Throwable failure) {
    LOG.log(
        Level.WARNING,
        "environment daemon " + stage + " failed: " + failure.getClass().getSimpleName());
  }

  static DaemonOperatingSystem toDomainOs(OperatingSystem os) {
    return switch (os) {
      case WINDOWS -> DaemonOperatingSystem.WINDOWS;
      case WSL -> DaemonOperatingSystem.WSL;
      case LINUX -> DaemonOperatingSystem.LINUX;
      case MACOS -> DaemonOperatingSystem.MACOS;
      default -> throw new ControlProtocolException("unsupported operating system: " + os);
    };
  }

  static String toDomainArch(Architecture arch) {
    return switch (arch) {
      case AMD64 -> "amd64";
      case ARM64 -> "arm64";
      default -> null;
    };
  }

  static RuntimeSnapshot toProtoSnapshot(DaemonRuntimeSnapshot snapshot) {
    RuntimeBody.Builder body = RuntimeBody.newBuilder();
    if (snapshot.status().daemonVersion() != null) {
      body.setDaemonVersion(snapshot.status().daemonVersion());
    }
    if (snapshot.status().update() != null) {
      DaemonUpdateProgress up = snapshot.status().update();
      UpdateProgress.Builder ub =
          UpdateProgress.newBuilder()
              .setTargetVersion(up.targetVersion())
              .setDownloadedBytes(up.downloadedBytes());
      if (up.totalBytes() != null) {
        ub.setTotalBytes(up.totalBytes());
      }
      body.setUpdate(ub.build());
    }
    return RuntimeSnapshot.newBuilder()
        .setRevision(snapshot.revision())
        .setStatus(body.build())
        .build();
  }

  static DaemonRuntimeSnapshot toDomainSnapshot(RuntimeSnapshot proto) {
    long revision = proto.getRevision();
    RuntimeBody body = proto.getStatus();
    String daemonVersion = body.getDaemonVersion();
    DaemonUpdateProgress update = null;
    if (body.hasUpdate()) {
      UpdateProgress up = body.getUpdate();
      update =
          new DaemonUpdateProgress(
              up.getTargetVersion(),
              up.getDownloadedBytes(),
              up.hasTotalBytes() ? up.getTotalBytes() : null);
    }
    return new DaemonRuntimeSnapshot(revision, new DaemonRuntimeStatus(daemonVersion, update));
  }

  static EnvironmentCapabilityResult toDomainResult(CapabilityResult result) {
    String callId = result.getCallId();
    boolean error = result.getError();
    String detailsJson = result.hasDetailsJson() ? result.getDetailsJson() : "{}";
    List<ResultContent> contents = new ArrayList<>(result.getContentsCount());
    for (Content c : result.getContentsList()) {
      switch (c.getBodyCase()) {
        case TEXT -> contents.add(new TextResultContent(c.getText().getText()));
        case JSON -> contents.add(new JsonResultContent(c.getJson().getJson()));
        case RESOURCE -> {
          ResourceContent rc = c.getResource();
          String uri = ResourceRef.blobUploadUri(UUID.fromString(rc.getUploadId()));
          ResourceRef ref =
              new ResourceRef(
                  uri,
                  rc.getMediaType(),
                  rc.hasName() ? rc.getName() : null,
                  rc.getSize(),
                  rc.getSha256());
          contents.add(new ResourceResultContent(ref, rc.hasPreview() ? rc.getPreview() : null));
        }
        default -> {}
      }
    }
    return new EnvironmentCapabilityResult(callId, contents, error, detailsJson);
  }

  static CapabilityResult toProtoResult(EnvironmentCapabilityResult result) {
    CapabilityResult.Builder builder =
        CapabilityResult.newBuilder()
            .setCallId(result.callId())
            .setError(result.error())
            .setDetailsJson(result.detailsJson());
    for (ResultContent content : result.contents()) {
      Content.Builder cb = Content.newBuilder();
      if (content instanceof TextResultContent text) {
        cb.setText(TextContent.newBuilder().setText(text.text()).build());
      } else if (content instanceof JsonResultContent json) {
        cb.setJson(JsonContent.newBuilder().setJson(json.json()).build());
      } else if (content instanceof ResourceResultContent res) {
        ResourceRef ref = res.resource();
        ResourceContent.Builder rcb =
            ResourceContent.newBuilder()
                .setUploadId(ref.blobUploadId() != null ? ref.blobUploadId().toString() : "")
                .setMediaType(ref.mediaType())
                .setSize(ref.size() != null ? ref.size() : 0L)
                .setSha256(ref.sha256() != null ? ref.sha256() : "");
        if (ref.name() != null) {
          rcb.setName(ref.name());
        }
        if (res.preview() != null) {
          rcb.setPreview(res.preview());
        }
        cb.setResource(rcb.build());
      }
      builder.addContents(cb.build());
    }
    return builder.build();
  }

  private static UUID requireCanonicalInvocationId(Envelope envelope) {
    if (!envelope.hasInvocationId()) {
      throw new ControlProtocolException("invocationId is required");
    }
    String text = envelope.getInvocationId();
    if (text == null || text.isBlank()) {
      throw new ControlProtocolException("invocationId must not be blank");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      throw new ControlProtocolException("invocationId must be a canonical lowercase UUID", error);
    }
    if (!parsed.toString().equals(text)) {
      throw new ControlProtocolException("invocationId must be a canonical lowercase UUID");
    }
    return parsed;
  }
}
