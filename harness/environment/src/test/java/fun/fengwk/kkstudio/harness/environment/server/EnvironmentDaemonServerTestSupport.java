package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.protocol.Architecture;
import fun.fengwk.kkstudio.harness.environment.protocol.Cancelled;
import fun.fengwk.kkstudio.harness.environment.protocol.Completed;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.EnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.protocol.Error;
import fun.fengwk.kkstudio.harness.environment.protocol.ExecutionExited;
import fun.fengwk.kkstudio.harness.environment.protocol.Failed;
import fun.fengwk.kkstudio.harness.environment.protocol.Heartbeat;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeAction;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeResult;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.protocol.OperatingSystem;
import fun.fengwk.kkstudio.harness.environment.protocol.Progress;
import fun.fengwk.kkstudio.harness.environment.protocol.Ready;
import fun.fengwk.kkstudio.harness.environment.protocol.RecoverNotFound;
import fun.fengwk.kkstudio.harness.environment.protocol.ResourceUploadCommit;
import fun.fengwk.kkstudio.harness.environment.protocol.ResourceUploadRequest;
import fun.fengwk.kkstudio.harness.environment.protocol.RuntimeStatus;
import fun.fengwk.kkstudio.harness.environment.protocol.Started;
import fun.fengwk.kkstudio.harness.environment.protocol.UpdatePhase;
import fun.fengwk.kkstudio.harness.environment.protocol.UpdateResult;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.testing.FakeEnvironmentTunnel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 核心测试的内存基座：可控 fake control channel / lease store，以及握手与 protobuf 帧构造辅助。 */
final class EnvironmentDaemonServerTestSupport {

  static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  static final UUID CALL_ONE = new UUID(0L, 9101L);
  static final UUID CALL_TWO = new UUID(0L, 9102L);
  static final String TOKEN = "registration-token";
  static final String OTHER_TOKEN = "other-token";

  static final String INSTANCE_ID = "33333333-3333-3333-3333-333333333333";
  static final String OTHER_INSTANCE_ID = "44444444-4444-4444-4444-444444444444";
  static final String DAEMON_VERSION = "1.0.9";

  static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DAEMON_VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX,
              "Asia/Shanghai",
              "dev",
              "/home/dev",
              "note",
              "/tmp/kk-studio",
              "amd64"));

  static final long MAX_RESOURCE_BYTES = 16L * 1024 * 1024;

  private EnvironmentDaemonServerTestSupport() {}

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }

  static void throwUnchecked(Throwable failure) {
    sneakyThrow(failure);
  }

  static Envelope readyEnvelope(long revision, DaemonRuntimeStatus status) {
    return readyEnvelope(ENVIRONMENT_ID, revision, status);
  }

  static Envelope readyEnvelope(
      EnvironmentId environmentId, long revision, DaemonRuntimeStatus status) {
    return readyEnvelope(environmentId, revision, status, OperatingSystem.LINUX);
  }

  static Envelope readyEnvelope(
      EnvironmentId environmentId, long revision, DaemonRuntimeStatus status, OperatingSystem os) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.READY)
        .setEnvironmentId(environmentId.value().toString())
        .setReady(
            Ready.newBuilder()
                .setDaemonVersion(DAEMON_VERSION)
                .setEnvironment(
                    EnvironmentInfo.newBuilder()
                        .setOperatingSystem(os)
                        .setTimeZone("Asia/Shanghai")
                        .setUserName("dev")
                        .setHomeDirectory("/home/dev")
                        .setNote("note")
                        .setTempDirectory("/tmp/kk-studio")
                        .setArchitecture(Architecture.AMD64)
                        .build())
                .setRuntimeStatus(
                    DaemonConnectionSession.toProtoSnapshot(
                        new DaemonRuntimeSnapshot(revision, status)))
                .build())
        .build();
  }

  static Envelope heartbeatEnvelope() {
    return heartbeatEnvelope(1L, DaemonRuntimeStatus.running(DAEMON_VERSION));
  }

  static Envelope heartbeatEnvelope(long revision, DaemonRuntimeStatus status) {
    return heartbeatEnvelope(ENVIRONMENT_ID, revision, status, null);
  }

  static Envelope heartbeatEnvelope(long revision, DaemonRuntimeStatus status, Long ideLeaseRound) {
    return heartbeatEnvelope(ENVIRONMENT_ID, revision, status, ideLeaseRound);
  }

  static Envelope heartbeatEnvelope(
      EnvironmentId environmentId, long revision, DaemonRuntimeStatus status, Long ideLeaseRound) {
    Heartbeat.Builder builder =
        Heartbeat.newBuilder()
            .setRuntimeStatus(
                DaemonConnectionSession.toProtoSnapshot(
                    new DaemonRuntimeSnapshot(revision, status)));
    if (ideLeaseRound != null) {
      builder.setIdeLeaseRound(ideLeaseRound);
    }
    return Envelope.newBuilder()
        .setMessageType(MessageType.HEARTBEAT)
        .setEnvironmentId(environmentId.value().toString())
        .setHeartbeat(builder.build())
        .build();
  }

  static Envelope runtimeStatusEnvelope(long revision, DaemonRuntimeStatus status) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.RUNTIME_STATUS)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setRuntimeStatus(
            RuntimeStatus.newBuilder()
                .setRuntimeStatus(
                    DaemonConnectionSession.toProtoSnapshot(
                        new DaemonRuntimeSnapshot(revision, status)))
                .build())
        .build();
  }

  static Envelope runtimeStatusEnvelope(
      long revision, String daemonVersion, String targetVersion, Long downloadedBytes) {
    DaemonUpdateProgress progress =
        targetVersion == null
            ? null
            : new DaemonUpdateProgress(targetVersion, downloadedBytes, null);
    return runtimeStatusEnvelope(revision, new DaemonRuntimeStatus(daemonVersion, progress));
  }

  record HeartbeatNotification(
      EnvironmentId environmentId, DaemonConnectionIdentity identity, Long ideLeaseRound) {}

  static Envelope startedEnvelope(UUID invocationId) {
    return startedEnvelope(invocationId, false);
  }

  static Envelope startedEnvelope(String invocationId) {
    return startedEnvelope(UUID.fromString(invocationId), false);
  }

  static Envelope startedEnvelope(String invocationId, boolean replayed) {
    return startedEnvelope(UUID.fromString(invocationId), replayed);
  }

  static Envelope startedEnvelope(UUID invocationId, boolean replayed) {
    Started.Builder started = Started.newBuilder();
    if (replayed) {
      started.setReplayed(true);
    }
    return Envelope.newBuilder()
        .setMessageType(MessageType.STARTED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setStarted(started.build())
        .build();
  }

  static Envelope progressEnvelope(UUID invocationId, String text) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.PROGRESS)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setProgress(
            Progress.newBuilder()
                .setResult(
                    DaemonConnectionSession.toProtoResult(
                        EnvironmentCapabilityResult.text(invocationId.toString(), text)))
                .build())
        .build();
  }

  static Envelope progressEnvelope(String invocationId, String text) {
    return progressEnvelope(UUID.fromString(invocationId), text);
  }

  static Envelope completedEnvelope(UUID invocationId, String text) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.COMPLETED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setCompleted(
            Completed.newBuilder()
                .setResult(
                    DaemonConnectionSession.toProtoResult(
                        EnvironmentCapabilityResult.text(invocationId.toString(), text)))
                .build())
        .build();
  }

  static Envelope completedEnvelope(String invocationId, String text) {
    return completedEnvelope(UUID.fromString(invocationId), text);
  }

  static Envelope completedResourceEnvelope(
      UUID invocationId, ResourceRef uploaded, String preview) {
    List<ResultContent> contents = List.of(new ResourceResultContent(uploaded, preview));
    return Envelope.newBuilder()
        .setMessageType(MessageType.COMPLETED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setCompleted(
            Completed.newBuilder()
                .setResult(
                    DaemonConnectionSession.toProtoResult(
                        new EnvironmentCapabilityResult(
                            invocationId.toString(), contents, false, "{}")))
                .build())
        .build();
  }

  static Envelope completedResourceEnvelope(
      String invocationId, ResourceRef uploaded, String preview) {
    return completedResourceEnvelope(UUID.fromString(invocationId), uploaded, preview);
  }

  static Envelope completedResourceEnvelope(UUID invocationId, List<ResourceRef> uploadedList) {
    List<ResultContent> contents =
        uploadedList.stream()
            .map(ref -> (ResultContent) new ResourceResultContent(ref, null))
            .toList();
    return Envelope.newBuilder()
        .setMessageType(MessageType.COMPLETED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setCompleted(
            Completed.newBuilder()
                .setResult(
                    DaemonConnectionSession.toProtoResult(
                        new EnvironmentCapabilityResult(
                            invocationId.toString(), contents, false, "{}")))
                .build())
        .build();
  }

  static Envelope completedResourceEnvelope(String invocationId, List<ResourceRef> uploadedList) {
    return completedResourceEnvelope(UUID.fromString(invocationId), uploadedList);
  }

  static Envelope failedEnvelope(UUID invocationId, String message) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.FAILED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setFailed(Failed.newBuilder().setMessage(message).build())
        .build();
  }

  static Envelope failedEnvelope(String invocationId, String message) {
    return failedEnvelope(UUID.fromString(invocationId), message);
  }

  static Envelope cancelledEnvelope(UUID invocationId) {
    return cancelledEnvelope(invocationId, "cancelled");
  }

  static Envelope cancelledEnvelope(UUID invocationId, String reason) {
    Cancelled.Builder cancelled = Cancelled.newBuilder();
    if (reason != null) {
      cancelled.setReason(reason);
    }
    return Envelope.newBuilder()
        .setMessageType(MessageType.CANCELLED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setCancelled(cancelled.build())
        .build();
  }

  static Envelope cancelledEnvelope(String invocationId) {
    return cancelledEnvelope(UUID.fromString(invocationId));
  }

  static Envelope cancelledEnvelope(String invocationId, String reason) {
    return cancelledEnvelope(UUID.fromString(invocationId), reason);
  }

  static Envelope executionExitedEnvelope(UUID invocationId) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.EXECUTION_EXITED)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setExecutionExited(ExecutionExited.getDefaultInstance())
        .build();
  }

  static Envelope executionExitedEnvelope(String invocationId) {
    return executionExitedEnvelope(UUID.fromString(invocationId));
  }

  static Envelope recoverNotFoundEnvelope(UUID invocationId) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.RECOVER_NOT_FOUND)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setRecoverNotFound(RecoverNotFound.getDefaultInstance())
        .build();
  }

  static Envelope recoverNotFoundEnvelope(String invocationId) {
    return recoverNotFoundEnvelope(UUID.fromString(invocationId));
  }

  static Envelope resourceUploadRequestEnvelope(
      UUID invocationId, UUID uploadId, String name, long size) {
    return resourceUploadRequestEnvelope(
        invocationId,
        uploadId,
        "application/octet-stream",
        name,
        size,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
  }

  static Envelope resourceUploadRequestEnvelope(
      String invocationId, UUID uploadId, String name, long size) {
    return resourceUploadRequestEnvelope(UUID.fromString(invocationId), uploadId, name, size);
  }

  static Envelope resourceUploadRequestEnvelope(
      UUID invocationId, UUID uploadId, String mediaType, String name, long size, String sha256) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.RESOURCE_UPLOAD_REQUEST)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setResourceUploadRequest(
            ResourceUploadRequest.newBuilder()
                .setTransferId(uploadId.toString())
                .setName(name)
                .setSize(size)
                .setSha256(sha256)
                .setMediaType(mediaType)
                .build())
        .build();
  }

  static Envelope resourceUploadRequestEnvelope(
      String invocationId, UUID uploadId, String mediaType, String name, long size, String sha256) {
    return resourceUploadRequestEnvelope(
        UUID.fromString(invocationId), uploadId, mediaType, name, size, sha256);
  }

  static Envelope resourceUploadCommitEnvelope(
      UUID invocationId, UUID uploadId, ResourceRef uploaded) {
    return resourceUploadCommitEnvelope(
        invocationId,
        uploadId,
        uploaded.blobUploadId() != null ? uploaded.blobUploadId() : uploadId);
  }

  static Envelope resourceUploadCommitEnvelope(
      String invocationId, UUID uploadId, ResourceRef uploaded) {
    return resourceUploadCommitEnvelope(UUID.fromString(invocationId), uploadId, uploaded);
  }

  static Envelope resourceUploadCommitEnvelope(UUID invocationId, UUID transferId, UUID uploadId) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.RESOURCE_UPLOAD_COMMIT)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setResourceUploadCommit(
            ResourceUploadCommit.newBuilder()
                .setTransferId(transferId.toString())
                .setUploadId(uploadId.toString())
                .build())
        .build();
  }

  static Envelope resourceUploadCommitEnvelope(
      String invocationId, UUID transferId, UUID uploadId) {
    return resourceUploadCommitEnvelope(UUID.fromString(invocationId), transferId, uploadId);
  }

  static Envelope errorEnvelope(String message) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.ERROR)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setError(Error.newBuilder().setMessage(message).build())
        .build();
  }

  static IdeCommand ideCommand(String viewId) {
    return IdeCommand.newBuilder().setAction(IdeAction.OPEN_VIEW).setViewId(viewId).build();
  }

  static IdeResult ideResult(String viewId, long epoch, String dir, String workspace) {
    return IdeResult.newBuilder()
        .setOk(true)
        .setViewId(viewId)
        .setBindingEpoch(epoch)
        .setDirectory(dir)
        .setWorkspacePath(workspace)
        .build();
  }

  static Envelope ideResultEnvelope(
      UUID invocationId, boolean ok, String viewId, long epoch, String dir, String workspace) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.IDE_RESULT)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setInvocationId(invocationId.toString())
        .setIdeResult(ideResult(viewId, epoch, dir, workspace))
        .build();
  }

  static Envelope updateResultEnvelope(UUID operationId, boolean ok, String message) {
    return updateResultEnvelope(
        operationId, ok ? UpdatePhase.UPDATE_PREPARED : UpdatePhase.UPDATE_FAILED, message);
  }

  static Envelope updateResultEnvelope(UUID operationId, UpdatePhase phase, String message) {
    UpdateResult.Builder builder =
        UpdateResult.newBuilder().setOperationId(operationId.toString()).setPhase(phase);
    if (message != null) {
      builder.setMessage(message);
    }
    return Envelope.newBuilder()
        .setMessageType(MessageType.UPDATE_RESULT)
        .setEnvironmentId(ENVIRONMENT_ID.value().toString())
        .setUpdateResult(builder.build())
        .build();
  }

  static EnvironmentCapabilityExecutionRequest capabilityRequest(UUID callId) {
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(callId.toString(), "{\"path\":\"/srv/repo/README.md\"}"),
        Duration.ofSeconds(5));
  }

  static final class Fixture {

    final FakeLeaseStore leaseStore = new FakeLeaseStore();
    final FakeTicketService ticketService = new FakeTicketService();
    final List<EnvironmentId> readyNotifications = new ArrayList<>();
    final List<DaemonConnectionIdentity> replacedInstances = new ArrayList<>();
    final List<HeartbeatNotification> heartbeatNotifications = new ArrayList<>();
    final List<EnvironmentId> updateResultEnvironments = new ArrayList<>();
    final List<DaemonUpdateResult> updateResults = new ArrayList<>();
    final AtomicReference<EnvironmentServerSettings> settings =
        new AtomicReference<>(defaultSettings());
    final EnvironmentDaemonServer server;
    private boolean registrationDirectoryFails;

    final Map<FakeControlChannel, DaemonSession> sessions = new ConcurrentHashMap<>();

    Throwable readyNotificationFailure;
    Throwable updateNotificationFailure;
    Throwable replacedNotificationFailure;

    Fixture() {
      this.server =
          new EnvironmentDaemonServer(
              leaseStore,
              token -> {
                if (registrationDirectoryFails) {
                  throw new IllegalStateException("directory unavailable");
                }
                if (TOKEN.equals(token)) {
                  return Optional.of(new DaemonRegistration(ENVIRONMENT_ID, "env-1"));
                }
                if (OTHER_TOKEN.equals(token)) {
                  return Optional.of(new DaemonRegistration(OTHER_ENVIRONMENT_ID, "env-2"));
                }
                return Optional.empty();
              },
              new EnvironmentSessionListener() {
                @Override
                public void onEnvironmentReady(EnvironmentId environmentId) {
                  if (readyNotificationFailure != null) {
                    throwUnchecked(readyNotificationFailure);
                  }
                  readyNotifications.add(environmentId);
                }

                @Override
                public void onDaemonInstanceReplaced(DaemonConnectionIdentity previous) {
                  replacedInstances.add(previous);
                  if (replacedNotificationFailure != null) {
                    throwUnchecked(replacedNotificationFailure);
                  }
                }

                @Override
                public void onHeartbeat(
                    EnvironmentId environmentId,
                    DaemonConnectionIdentity identity,
                    Long ideLeaseRound) {
                  heartbeatNotifications.add(
                      new HeartbeatNotification(environmentId, identity, ideLeaseRound));
                }
              },
              (environmentId, result) -> {
                if (updateNotificationFailure != null) {
                  throwUnchecked(updateNotificationFailure);
                }
                updateResultEnvironments.add(environmentId);
                updateResults.add(result);
              },
              ticketService,
              settings::get);
    }

    void updateSettings(long ttlSeconds, long cleanupIntervalSeconds) {
      settings.set(
          new EnvironmentServerSettings(
              Duration.ofSeconds(60), 16L * 1024 * 1024, ttlSeconds, cleanupIntervalSeconds));
    }

    private static EnvironmentServerSettings defaultSettings() {
      return new EnvironmentServerSettings(
          Duration.ofSeconds(60), 16L * 1024 * 1024, 259200L, 1800L);
    }

    void failRegistrationDirectory() {
      registrationDirectoryFails = true;
    }

    FakeControlChannel openSession(String connectionId) {
      return openSession(connectionId, INSTANCE_ID, TOKEN);
    }

    FakeControlChannel openSession(String connectionId, String daemonInstanceId) {
      return openSession(connectionId, daemonInstanceId, TOKEN);
    }

    FakeControlChannel openSession(String connectionId, String daemonInstanceId, String token) {
      DaemonAdmission admission = server.admit(new DaemonHandshake(token, daemonInstanceId));
      if (!(admission instanceof DaemonAdmission.Admitted admitted)) {
        throw new IllegalStateException("admission failed: " + admission);
      }
      FakeControlChannel channel = new FakeControlChannel(connectionId);
      DaemonSession session = server.open(admitted, channel, channel.tunnel);
      sessions.put(channel, session);
      return channel;
    }

    FakeControlChannel connectReady(String connectionId) {
      return connectReady(connectionId, INSTANCE_ID);
    }

    FakeControlChannel connectReady(String connectionId, String daemonInstanceId) {
      return connectReady(ENVIRONMENT_ID, connectionId, daemonInstanceId, TOKEN);
    }

    FakeControlChannel connectReady(
        EnvironmentId environmentId, String connectionId, String daemonInstanceId, String token) {
      for (Map.Entry<FakeControlChannel, DaemonSession> entry : sessions.entrySet()) {
        if (connectionId.equals(entry.getKey().connectionId)) {
          entry.getValue().close();
        }
      }
      DaemonAdmission admission = server.admit(new DaemonHandshake(token, daemonInstanceId));
      if (!(admission instanceof DaemonAdmission.Admitted admitted)) {
        throw new IllegalStateException("admission failed: " + admission);
      }
      FakeControlChannel channel = new FakeControlChannel(connectionId);
      DaemonSession session = server.open(admitted, channel, channel.tunnel);
      sessions.put(channel, session);
      session.receive(
          readyEnvelope(environmentId, 1L, DaemonRuntimeStatus.running(DAEMON_VERSION)));
      return channel;
    }

    FakeControlChannel reconnectReady(String connectionId) {
      leaseStore.activeLeaseToken = false;
      return connectReady(connectionId);
    }

    void receive(FakeControlChannel channel, Envelope envelope) {
      DaemonSession session = sessions.get(channel);
      if (session != null) {
        session.receive(envelope);
      }
    }

    void receiveReady(FakeControlChannel channel) {
      receiveReady(channel, 1L, DaemonRuntimeStatus.running(DAEMON_VERSION));
    }

    void receiveReady(FakeControlChannel channel, long revision, DaemonRuntimeStatus status) {
      receive(channel, readyEnvelope(ENVIRONMENT_ID, revision, status));
    }

    DaemonSession sessionOf(FakeControlChannel channel) {
      return sessions.get(channel);
    }

    void close(String connectionId) {
      DaemonSession session = null;
      for (Map.Entry<FakeControlChannel, DaemonSession> entry : sessions.entrySet()) {
        if (entry.getKey().connectionId().equals(connectionId)) {
          session = entry.getValue();
          break;
        }
      }
      if (session != null) {
        session.closed();
      }
    }

    void close(FakeControlChannel channel) {
      DaemonSession session = sessions.get(channel);
      if (session != null) {
        session.closed();
      }
    }
  }

  static final class FakeLeaseStore implements DaemonLeaseStore {

    boolean holdsReadyLease = true;
    boolean heartbeatResult = true;
    boolean activeLeaseToken = true;
    boolean acquired;
    boolean disconnected;
    boolean released;
    boolean databaseUnavailable;
    boolean markReadyResult = true;
    boolean rejected;
    boolean retryLater;
    DaemonCapabilities readyCapabilities;
    final List<EnvironmentId> runtimeStatusChanges = new ArrayList<>();
    boolean runtimeStatusNotificationFails;
    boolean runtimeStatusFenceLost;

    Throwable disconnectFailure;

    @Override
    public LeaseBindResult tryAcquire(
        EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      if (rejected) {
        return new LeaseBindResult.Rejected("route rejected");
      }
      if (retryLater) {
        return new LeaseBindResult.RetryLater("route retry later");
      }
      if (!TOKEN.equals(registrationToken) && !OTHER_TOKEN.equals(registrationToken)) {
        return new LeaseBindResult.Rejected("invalid registration token");
      }
      acquired = true;
      return new LeaseBindResult.Acquired(UUID.randomUUID());
    }

    @Override
    public boolean markReady(
        EnvironmentId environmentId,
        UUID leaseToken,
        DaemonCapabilities capabilities,
        Duration leaseDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      readyCapabilities = capabilities;
      return markReadyResult;
    }

    @Override
    public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
      return heartbeatResult;
    }

    @Override
    public boolean runtimeStatusChanged(EnvironmentId environmentId, UUID leaseToken) {
      if (runtimeStatusNotificationFails) {
        throw new IllegalStateException("runtime status notification unavailable");
      }
      if (runtimeStatusFenceLost || !holdsReadyLease) {
        return false;
      }
      runtimeStatusChanges.add(environmentId);
      return true;
    }

    @Override
    public boolean disconnect(
        EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      if (disconnectFailure != null) {
        throwUnchecked(disconnectFailure);
      }
      disconnected = true;
      return true;
    }

    @Override
    public boolean release(EnvironmentId environmentId, UUID leaseToken) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      released = true;
      return true;
    }

    @Override
    public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      return holdsReadyLease;
    }

    @Override
    public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
      if (databaseUnavailable) {
        throw new IllegalStateException("database unavailable");
      }
      return activeLeaseToken;
    }
  }

  static final class RecordingListener implements EnvironmentCapabilityExecutionListener {

    final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    EnvironmentCapabilityResult completed;
    Throwable error;
    int errorCount;
    int exitConfirmations;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      completed = result;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      errorCount++;
    }

    @Override
    public void onExitConfirmed() {
      exitConfirmations++;
    }

    String completedText() {
      return completed == null ? null : ((TextResultContent) completed.contents().get(0)).text();
    }

    String partialText(int index) {
      return ((TextResultContent) partials.get(index).contents().get(0)).text();
    }
  }

  static class SerialListener implements EnvironmentCapabilityExecutionListener {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      events.add("partial:" + ((TextResultContent) partial.contents().get(0)).text());
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      events.add("complete:" + ((TextResultContent) result.contents().get(0)).text());
    }

    @Override
    public void onError(Throwable error) {
      events.add("error:" + error.getClass().getSimpleName());
    }

    @Override
    public void onExitConfirmed() {
      events.add("exit");
    }

    List<String> events() {
      return List.copyOf(events);
    }
  }

  static final class FakeControlChannel implements DaemonControlChannel {

    final EnvironmentTunnel tunnel =
        new FakeEnvironmentTunnel(new DaemonConnectionIdentity(ENVIRONMENT_ID, INSTANCE_ID));
    private final String connectionId;
    private final List<Envelope> envelopes = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;
    private volatile boolean closed = false;
    private volatile int closeCount = 0;

    volatile boolean busyNextOffer;
    volatile boolean failNextOffer;
    volatile boolean holdNextOffer;
    Throwable closeFailure;

    private volatile int closeAfterFlushCount = 0;
    private volatile int drainCount = 0;

    private final CountDownLatch offerEntered = new CountDownLatch(1);
    private final CountDownLatch offerReleased = new CountDownLatch(1);

    FakeControlChannel(String connectionId) {
      this.connectionId = connectionId;
    }

    public String connectionId() {
      return connectionId;
    }

    @Override
    public boolean isOpen() {
      return open && !closed;
    }

    @Override
    public DaemonOfferResult offer(Envelope envelope) {
      if (holdNextOffer) {
        holdNextOffer = false;
        offerEntered.countDown();
        try {
          if (!offerReleased.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("offer hold was not released");
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("offer hold interrupted", error);
        }
      }
      if (!isOpen()) {
        return DaemonOfferResult.CLOSED;
      }
      ControlProtocolCodec.encodeFrame(envelope);
      if (busyNextOffer) {
        busyNextOffer = false;
        return DaemonOfferResult.BUSY;
      }
      if (failNextOffer) {
        failNextOffer = false;
        throw new IllegalStateException("transport send failed");
      }
      envelopes.add(envelope);
      return DaemonOfferResult.ACCEPTED;
    }

    @Override
    public void drain() {
      drainCount++;
      closeAfterFlush();
    }

    int drainCount() {
      return drainCount;
    }

    @Override
    public void closeAfterFlush() {
      closeAfterFlushCount++;
      close();
    }

    int closeAfterFlushCount() {
      return closeAfterFlushCount;
    }

    @Override
    public void close() {
      open = false;
      closed = true;
      closeCount++;
      if (closeFailure != null) {
        throwUnchecked(closeFailure);
      }
    }

    List<Envelope> envelopes() {
      return envelopes;
    }

    List<MessageType> messageTypes() {
      return envelopes.stream().map(Envelope::getMessageType).toList();
    }

    long countOf(MessageType type) {
      return envelopes.stream().filter(envelope -> envelope.getMessageType() == type).count();
    }

    Envelope lastEnvelope() {
      return envelopes.isEmpty() ? null : envelopes.get(envelopes.size() - 1);
    }

    boolean closed() {
      return closed;
    }

    int closeCount() {
      return closeCount;
    }

    boolean awaitOfferEntered() {
      try {
        return offerEntered.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    void releaseOffer() {
      offerReleased.countDown();
    }
  }

  static final class FakeTicketService implements DaemonResourceTicketService {

    final List<TransferRequest> reserves = new ArrayList<>();
    final List<UUID> commits = new ArrayList<>();
    final List<UUID> releases = new ArrayList<>();
    boolean failNextReserve;
    boolean pendingReserve;
    UUID pendingUploadId;

    @Override
    public Ticket reserve(
        EnvironmentId environmentId, String invocationId, TransferRequest request) {
      reserves.add(request);
      if (failNextReserve) {
        failNextReserve = false;
        return new Ticket.Failed("storage is unavailable");
      }
      if (pendingReserve) {
        UUID uploadId = pendingUploadId == null ? request.transferId() : pendingUploadId;
        return new Ticket.Pending(
            uploadId,
            new DaemonPresignedPut(
                "PUT",
                "https://storage.invalid/uploads/" + uploadId,
                Map.of("If-None-Match", "*")));
      }
      return new Ticket.Ready(request.transferId());
    }

    @Override
    public Ticket commit(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      commits.add(uploadId);
      return new Ticket.Ready(uploadId);
    }

    @Override
    public void release(EnvironmentId environmentId, String invocationId, UUID uploadId) {
      if (uploadId != null) {
        releases.add(uploadId);
      }
    }
  }
}
