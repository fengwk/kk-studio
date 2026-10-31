package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdatePhase;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;
import fun.fengwk.kkstudio.harness.environment.protocol.Update;
import fun.fengwk.kkstudio.harness.environment.protocol.UpdateResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 受管更新控制面协调器：管理更新互斥窗口与更新阶段回执通知。 */
final class DaemonUpdateCoordinator {

  private final Object lock = new Object();
  private final Map<EnvironmentId, String> updatingByEnvironment = new HashMap<>();

  void beginUpdate(
      EnvironmentId environmentId,
      String operationId,
      DaemonConnectionSession session,
      int inFlightCount) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    if (operationId.isBlank()) {
      throw new IllegalArgumentException("operationId must not be blank");
    }
    synchronized (lock) {
      if (session == null || !session.isReady()) {
        throw new EnvironmentCapabilityUnavailableException(
            environmentId + " is not ready for update");
      }
      String existing = updatingByEnvironment.get(environmentId);
      if (existing != null) {
        if (existing.equals(operationId)) {
          return;
        }
        throw new EnvironmentCapabilityBusyException(
            "environment " + environmentId + " already has an update in progress");
      }
      if (inFlightCount > 0) {
        throw new EnvironmentCapabilityBusyException(
            "environment " + environmentId + " has " + inFlightCount + " in-flight invocations");
      }
      updatingByEnvironment.put(environmentId, operationId);
    }
  }

  void endUpdate(EnvironmentId environmentId, String operationId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    synchronized (lock) {
      String existing = updatingByEnvironment.get(environmentId);
      if (operationId.equals(existing)) {
        updatingByEnvironment.remove(environmentId);
      }
    }
  }

  boolean isUpdating(EnvironmentId environmentId) {
    synchronized (lock) {
      return updatingByEnvironment.containsKey(environmentId);
    }
  }

  String updatingOperationId(EnvironmentId environmentId) {
    synchronized (lock) {
      return updatingByEnvironment.get(environmentId);
    }
  }

  void sendUpdate(
      EnvironmentId environmentId, DaemonConnectionSession session, DaemonUpdateCommand command) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(command, "command");
    synchronized (lock) {
      String updating = updatingByEnvironment.get(environmentId);
      if (!command.operationId().equals(updating)) {
        throw new EnvironmentCapabilityBusyException(
            "environment " + environmentId + " is not in update " + command.operationId());
      }
    }
    if (session == null || !session.isReady()) {
      throw new EnvironmentCapabilityUnavailableException(
          environmentId + " is not ready for update");
    }
    Update updateMessage =
        Update.newBuilder()
            .setOperationId(command.operationId())
            .setTargetVersion(command.targetVersion())
            .setArtifactUrl(command.artifactUrl())
            .setArtifactSha256(command.artifactSha256())
            .build();
    Envelope envelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.UPDATE)
            .setEnvironmentId(environmentId.value().toString())
            .setUpdate(updateMessage)
            .build();
    DaemonOfferResult outcome;
    synchronized (session.stateLock()) {
      if (!session.isReady()) {
        throw new EnvironmentCapabilityUnavailableException(
            environmentId + " is not ready for update");
      }
      outcome = session.offerResult(envelope);
    }
    if (outcome == DaemonOfferResult.ACCEPTED) {
      return;
    }
    if (outcome == DaemonOfferResult.BUSY) {
      throw new EnvironmentCapabilityBusyException(
          "daemon outbound queue is full for environment " + environmentId);
    }
    throw new EnvironmentCapabilityUnavailableException(environmentId + " is not ready for update");
  }

  void handleUpdateResult(
      EnvironmentId environmentId,
      UpdateResult result,
      EnvironmentUpdateListener updateListener,
      List<Runnable> deferred) {
    DaemonUpdatePhase phase =
        switch (result.getPhase()) {
          case UPDATE_ACCEPTED -> DaemonUpdatePhase.ACCEPTED;
          case UPDATE_PREPARED -> DaemonUpdatePhase.PREPARED;
          case UPDATE_FAILED -> DaemonUpdatePhase.FAILED;
          default -> throw new IllegalArgumentException(
              "unrecognized update phase: " + result.getPhase());
        };
    String message =
        result.hasMessage() && !result.getMessage().isBlank() ? result.getMessage() : null;
    DaemonUpdateResult domainResult =
        new DaemonUpdateResult(result.getOperationId(), phase, message);
    deferred.add(
        () -> {
          try {
            updateListener.onUpdateResult(environmentId, domainResult);
          } catch (RuntimeException error) {
            DaemonConnectionSession.logFailure("update result notification", error);
          }
        });
  }
}
