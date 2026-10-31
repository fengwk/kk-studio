package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeLease;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeResult;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** IDE 命令控制面协调器：处理 IDE_COMMAND 下发、IDE_RESULT 匹配、IDE_LEASE 下发与断开收敛。 */
final class DaemonIdeCoordinator {

  private static final class PendingIdeCall {
    private final DaemonConnectionSession session;
    private final EnvironmentId environmentId;
    private final UUID invocationId;
    private final CompletableFuture<IdeResult> reply = new CompletableFuture<>();

    private PendingIdeCall(
        DaemonConnectionSession session, EnvironmentId environmentId, UUID invocationId) {
      this.session = session;
      this.environmentId = environmentId;
      this.invocationId = invocationId;
    }
  }

  private final Object lock = new Object();
  private final Map<UUID, PendingIdeCall> pendingCalls = new HashMap<>();

  DaemonIdeCall requestIde(
      EnvironmentId environmentId,
      DaemonConnectionSession session,
      IdeCommand command,
      DaemonLeaseStore leaseStore) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(command, "command");

    if (!session.isReady()) {
      throw new EnvironmentCapabilityUnavailableException(environmentId + " is not ready for ide");
    }
    UUID leaseToken = session.leaseToken();
    if (leaseToken == null) {
      throw new EnvironmentCapabilityUnavailableException(environmentId + " is not ready for ide");
    }
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw new EnvironmentCapabilityUnavailableException(environmentId + " is not ready for ide");
    }

    UUID invocationId = UUID.randomUUID();
    PendingIdeCall pending = new PendingIdeCall(session, environmentId, invocationId);
    DaemonOfferResult outcome;
    RuntimeException sendFailure = null;
    synchronized (session.stateLock()) {
      if (!session.isReady() || !leaseToken.equals(session.leaseToken())) {
        throw new EnvironmentCapabilityUnavailableException(
            environmentId + " is not ready for ide");
      }
      synchronized (lock) {
        pendingCalls.put(invocationId, pending);
      }
      Envelope envelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.IDE_COMMAND)
              .setEnvironmentId(environmentId.value().toString())
              .setInvocationId(invocationId.toString())
              .setIdeCommand(command)
              .build();
      try {
        outcome = session.offerResult(envelope);
      } catch (ControlProtocolException error) {
        abandonIde(invocationId);
        throw error;
      } catch (RuntimeException error) {
        outcome = DaemonOfferResult.CLOSED;
        sendFailure = error;
      }
    }
    if (sendFailure != null) {
      abandonIde(invocationId);
      session.close();
      throw new EnvironmentCapabilitySendUncertainException(
          "IDE command delivery outcome is uncertain for environment " + environmentId);
    }
    if (outcome == DaemonOfferResult.ACCEPTED) {
      return new DaemonIdeCall(session.identity(), invocationId, pending.reply);
    }
    abandonIde(invocationId);
    if (outcome == DaemonOfferResult.BUSY) {
      throw new EnvironmentCapabilityBusyException(
          "daemon outbound queue is full for environment " + environmentId);
    }
    throw new EnvironmentCapabilityUnavailableException(environmentId + " is not ready for ide");
  }

  void abandonIde(UUID invocationId) {
    synchronized (lock) {
      pendingCalls.remove(invocationId);
    }
  }

  void handleIdeResult(
      UUID invocationId,
      IdeResult result,
      DaemonConnectionSession session,
      List<Runnable> deferred) {
    PendingIdeCall pending;
    synchronized (lock) {
      PendingIdeCall candidate = pendingCalls.get(invocationId);
      if (candidate != null && candidate.session == session) {
        pendingCalls.remove(invocationId);
        pending = candidate;
      } else {
        pending = null;
      }
    }
    if (pending != null) {
      deferred.add(() -> pending.reply.complete(result));
    }
  }

  void failPendingIdeCalls(DaemonConnectionSession session) {
    List<PendingIdeCall> abandoned = new ArrayList<>();
    synchronized (lock) {
      Iterator<Map.Entry<UUID, PendingIdeCall>> iterator = pendingCalls.entrySet().iterator();
      while (iterator.hasNext()) {
        PendingIdeCall pending = iterator.next().getValue();
        if (pending.session == session) {
          iterator.remove();
          abandoned.add(pending);
        }
      }
    }
    for (PendingIdeCall pending : abandoned) {
      pending.reply.completeExceptionally(
          new EnvironmentCapabilitySendUncertainException(
              "environment connection closed before IDE reply; outcome is uncertain and will not be"
                  + " replayed"));
    }
  }

  void failPendingIdeCalls(EnvironmentId environmentId) {
    List<PendingIdeCall> abandoned = new ArrayList<>();
    synchronized (lock) {
      Iterator<Map.Entry<UUID, PendingIdeCall>> iterator = pendingCalls.entrySet().iterator();
      while (iterator.hasNext()) {
        PendingIdeCall pending = iterator.next().getValue();
        if (pending.environmentId.equals(environmentId)) {
          iterator.remove();
          abandoned.add(pending);
        }
      }
    }
    for (PendingIdeCall pending : abandoned) {
      pending.reply.completeExceptionally(
          new EnvironmentCapabilitySendUncertainException(
              "environment connection closed before IDE reply; outcome is uncertain and will not be"
                  + " replayed"));
    }
  }

  void sendIdeLease(
      EnvironmentId environmentId,
      DaemonConnectionSession session,
      DaemonConnectionIdentity expectedIdentity,
      IdeLease ideLease,
      DaemonLeaseStore leaseStore) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(ideLease, "ideLease");

    UUID leaseToken = session.leaseToken();
    if (!session.isReady() || leaseToken == null) {
      throw new EnvironmentCapabilityUnavailableException(
          environmentId + " is not ready for IDE_LEASE");
    }
    if (!expectedIdentity.equals(session.identity())) {
      throw new EnvironmentCapabilityUnavailableException(
          environmentId + " connection identity changed");
    }
    boolean holdsReady;
    try {
      holdsReady = leaseStore.holdsReadyLease(environmentId, leaseToken);
    } catch (RuntimeException error) {
      throw new EnvironmentCapabilityUnavailableException(
          "database unavailable: " + error.getMessage(), error);
    }
    if (!holdsReady) {
      throw new EnvironmentCapabilityUnavailableException(
          environmentId + " is not ready for IDE_LEASE");
    }
    DaemonOfferResult outcome;
    synchronized (session.stateLock()) {
      if (!session.isReady()
          || !leaseToken.equals(session.leaseToken())
          || !expectedIdentity.equals(session.identity())) {
        throw new EnvironmentCapabilityUnavailableException(
            environmentId + " is not ready for IDE_LEASE");
      }
      Envelope envelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.IDE_LEASE)
              .setEnvironmentId(environmentId.value().toString())
              .setIdeLease(ideLease)
              .build();
      outcome = session.offerResult(envelope);
    }
    if (outcome == DaemonOfferResult.ACCEPTED) {
      return;
    }
    if (outcome == DaemonOfferResult.BUSY) {
      throw new EnvironmentCapabilityBusyException(
          "daemon outbound queue is full for environment " + environmentId);
    }
    throw new EnvironmentCapabilityUnavailableException(
        environmentId + " is not ready for IDE_LEASE");
  }

  int activeIdeCount() {
    synchronized (lock) {
      return pendingCalls.size();
    }
  }
}
