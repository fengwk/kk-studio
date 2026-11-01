package fun.fengwk.kkstudio.harness.environment.server;

import tools.jackson.databind.JsonNode;

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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.ExpectedFile;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeLease;
import fun.fengwk.kkstudio.harness.environment.protocol.Invoke;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.protocol.Recover;
import fun.fengwk.kkstudio.harness.environment.protocol.ResourceBudgets;
import fun.fengwk.kkstudio.harness.environment.protocol.Settings;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnels;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Environment daemon 的服务端会话核心：组合准入与会话注册、单连接会话、调用跟踪、上传协调、IDE 命令与更新控制。 */
public final class EnvironmentDaemonServer
    implements DaemonSessions, EnvironmentTunnels, EnvironmentCapabilityTransport {

  private static final Logger LOG = System.getLogger(EnvironmentDaemonServer.class.getName());

  public static final int MAX_INVOCATION_TOMBSTONES =
      DaemonInvocationTracker.MAX_INVOCATION_TOMBSTONES;

  private record PendingAdmission(UUID leaseToken, String registrationToken, String displayName) {}

  private final DaemonLeaseStore leaseStore;
  private final DaemonRegistrationDirectory registrationDirectory;
  private final EnvironmentSessionListener sessionListener;
  private final EnvironmentUpdateListener updateListener;
  private final DaemonResourceTicketService ticketService;
  private final Supplier<EnvironmentServerSettings> settingsSupplier;

  private final DaemonInvocationTracker invocationTracker = new DaemonInvocationTracker();
  private final DaemonTransferCoordinator transferCoordinator = new DaemonTransferCoordinator();
  private final DaemonIdeCoordinator ideCoordinator = new DaemonIdeCoordinator();
  private final DaemonUpdateCoordinator updateCoordinator = new DaemonUpdateCoordinator();

  private final Object inventory = new Object();
  private final Map<EnvironmentId, DaemonConnectionSession> sessions = new HashMap<>();
  private final Map<EnvironmentId, String> daemonInstances = new HashMap<>();
  private final Map<DaemonAdmission.Admitted, PendingAdmission> pendingAdmissions =
      new ConcurrentHashMap<>();
  private final AtomicLong connectionGenerations = new AtomicLong();
  private volatile boolean draining;

  public EnvironmentDaemonServer(
      DaemonLeaseStore leaseStore,
      DaemonRegistrationDirectory registrationDirectory,
      EnvironmentSessionListener sessionListener,
      EnvironmentUpdateListener updateListener,
      DaemonResourceTicketService ticketService,
      Supplier<EnvironmentServerSettings> settings) {
    this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
    this.registrationDirectory =
        Objects.requireNonNull(registrationDirectory, "registrationDirectory");
    this.sessionListener = Objects.requireNonNull(sessionListener, "sessionListener");
    this.updateListener = Objects.requireNonNull(updateListener, "updateListener");
    this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    this.settingsSupplier = Objects.requireNonNull(settings, "settings");
  }

  private static void logFailure(String stage, Throwable failure) {
    LOG.log(
        Level.WARNING,
        "environment daemon " + stage + " failed: " + failure.getClass().getSimpleName());
  }

  @Override
  public DaemonAdmission admit(DaemonHandshake handshake) {
    Objects.requireNonNull(handshake, "handshake");
    if (draining) {
      return new DaemonAdmission.RetryLater(Duration.ofSeconds(5));
    }
    String token = requireNonBlank(handshake.registrationToken(), "registrationToken");
    String instanceId = requireNonBlank(handshake.daemonInstanceId(), "daemonInstanceId");

    Optional<DaemonRegistration> registrationOpt =
        registrationDirectory.findByRegistrationToken(token);
    if (registrationOpt.isEmpty()) {
      return new DaemonAdmission.Rejected();
    }
    DaemonRegistration registration = registrationOpt.get();
    EnvironmentId environmentId = registration.environmentId();

    DaemonConnectionSession existing = sessionOf(environmentId);
    if (existing != null && existing.leaseToken() != null && existing.isReady()) {
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
        String replacedInstanceId = replaceDaemonInstance(environmentId, instanceId);
        if (replacedInstanceId != null) {
          List<ActiveInvocation> abandoned =
              invocationTracker.takeAbandonedInvocations(environmentId);
          for (ActiveInvocation active : abandoned) {
            try {
              active.listener.onError(
                  new EnvironmentCapabilitySendUncertainException(
                      "Environment daemon instance changed for "
                          + environmentId
                          + "; capability invocation outcome is uncertain."));
            } catch (Throwable ignored) {
            }
            transferCoordinator.releaseAllTransfers(active, ticketService);
          }
          try {
            sessionListener.onDaemonInstanceReplaced(
                new DaemonConnectionIdentity(environmentId, replacedInstanceId));
          } catch (RuntimeException error) {
            logFailure("daemon instance replaced notification", error);
          }
        }
        DaemonAdmission.Admitted admitted = new DaemonAdmission.Admitted(environmentId, instanceId);
        pendingAdmissions.put(
            admitted,
            new PendingAdmission(acquired.leaseToken(), token, registration.displayName()));
        yield admitted;
      }
    };
  }

  @Override
  public void abandon(DaemonAdmission.Admitted admitted) {
    Objects.requireNonNull(admitted, "admitted");
    PendingAdmission pending = pendingAdmissions.remove(admitted);
    if (pending != null) {
      try {
        leaseStore.disconnect(admitted.environmentId(), pending.leaseToken, Duration.ZERO);
      } catch (RuntimeException ignored) {
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
            sessionListener,
            updateListener,
            ticketService,
            settingsSupplier,
            invocationTracker,
            transferCoordinator,
            ideCoordinator,
            updateCoordinator,
            this::removeSession);
    EnvironmentServerSettings currentSettings = settings();
    session.initializeSettings(currentSettings);

    Settings settingsMessage =
        Settings.newBuilder()
            .setDisplayName(pending.displayName)
            .setResourceBudgets(
                ResourceBudgets.newBuilder()
                    .setMaxResourceBytes(currentSettings.maxResourceBytes())
                    .setTemporaryResourceTtlSeconds(currentSettings.temporaryResourceTtlSeconds())
                    .setTemporaryResourceCleanupIntervalSeconds(
                        currentSettings.temporaryResourceCleanupIntervalSeconds())
                    .build())
            .build();
    Envelope settingsEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.SETTINGS)
            .setEnvironmentId(admitted.environmentId().value().toString())
            .setSettings(settingsMessage)
            .build();

    synchronized (inventory) {
      sessions.put(admitted.environmentId(), session);
    }
    if (!session.offer(settingsEnvelope)) {
      session.close();
    }
    return session;
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
    UUID invocationId = UUID.fromString(request.call().id());

    if (updateCoordinator.isUpdating(environmentId)) {
      throw new EnvironmentCapabilityBusyException(
          "environment " + environmentId + " is being updated");
    }
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null || !session.isReady()) {
      throw unavailable(environmentId, descriptor.id().value());
    }
    validateRequiredWorkdirShape(session.daemonOperatingSystem(), descriptor, request.call());

    UUID leaseToken = session.leaseToken();
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw unavailable(environmentId, descriptor.id().value());
    }

    ActiveInvocation active =
        new ActiveInvocation(
            environmentId,
            invocationId,
            listener,
            settings().heartbeatTimeout(),
            session.daemonInstanceId(),
            this::expire,
            () -> sessionOf(environmentId));

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

      Invoke.Builder invokeBuilder =
          Invoke.newBuilder()
              .setCapabilityId(descriptor.id().value())
              .setArgumentsJson(request.call().argumentsJson())
              .setTimeoutMillis(request.timeout().toMillis());
      if (request.expectedFile() != null) {
        invokeBuilder.setExpectedFile(
            ExpectedFile.newBuilder()
                .setPath(request.expectedFile().path())
                .setSha256(request.expectedFile().sha256())
                .build());
      }
      Envelope invokeEnvelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.INVOKE)
              .setEnvironmentId(environmentId.value().toString())
              .setInvocationId(invocationId.toString())
              .setInvoke(invokeBuilder.build())
              .build();
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
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null || !session.isReady()) {
      throw unavailable(environmentId, "invocation " + invocationId);
    }
    UUID leaseToken = session.leaseToken();
    if (leaseToken == null) {
      throw unavailable(environmentId, "invocation " + invocationId);
    }
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw unavailable(environmentId, "invocation " + invocationId);
    }

    ActiveInvocation active =
        new ActiveInvocation(
            environmentId,
            invocationId,
            listener,
            settings().heartbeatTimeout(),
            session.daemonInstanceId(),
            this::expire,
            () -> sessionOf(environmentId));

    DaemonOfferResult outcome = null;
    RuntimeException sendFailure = null;
    synchronized (session.stateLock()) {
      if (!session.isReady() || !leaseToken.equals(session.leaseToken())) {
        throw unavailable(environmentId, "invocation " + invocationId);
      }
      invocationTracker.register(active);

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
        logFailure("control drain", error);
      }
      UUID leaseToken = session.leaseToken();
      if (leaseToken != null) {
        try {
          leaseStore.release(session.environmentId(), leaseToken);
        } catch (RuntimeException error) {
          logFailure("lease release", error);
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
    if (leaseToken == null) {
      return false;
    }
    try {
      return leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      return false;
    }
  }

  public Optional<DaemonRuntimeSnapshot> runtimeStatus(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    DaemonConnectionSession session = sessionOf(environmentId);
    if (session == null || !session.isReady()) {
      return Optional.empty();
    }
    UUID leaseToken = session.leaseToken();
    if (leaseToken == null) {
      return Optional.empty();
    }
    try {
      if (!leaseStore.holdsReadyLease(environmentId, leaseToken)) {
        return Optional.empty();
      }
    } catch (RuntimeException error) {
      return Optional.empty();
    }
    return session.runtimeStatus();
  }

  public Set<EnvironmentId> readyEnvironments() {
    Set<EnvironmentId> ready = new HashSet<>();
    synchronized (inventory) {
      for (Map.Entry<EnvironmentId, DaemonConnectionSession> entry : sessions.entrySet()) {
        if (entry.getValue().isReady()) {
          ready.add(entry.getKey());
        }
      }
    }
    return ready;
  }

  public Optional<DaemonConnectionIdentity> connectionIdentity(EnvironmentId environmentId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    return session != null && session.isReady()
        ? Optional.of(session.identity())
        : Optional.empty();
  }

  public void beginUpdate(EnvironmentId environmentId, String operationId) {
    DaemonConnectionSession session = sessionOf(environmentId);
    int inFlight = invocationTracker.activeInvocationCount(environmentId);
    updateCoordinator.beginUpdate(environmentId, operationId, session, inFlight);
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
    if (operatingSystem == null) {
      throw new EnvironmentCapabilityUnavailableException(
          "target daemon operating system is not known; cannot validate workdir for "
              + descriptor.id().value());
    }
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

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
