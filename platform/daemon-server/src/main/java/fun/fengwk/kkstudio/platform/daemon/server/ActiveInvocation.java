package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.platform.daemon.protocol.Cancel;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 一次能力调用的所有权记录：终态唯一、取消意图与已送达代际分离，cancel/expire/连接清理竞争只产生一个赢家。 */
final class ActiveInvocation implements EnvironmentCapabilityExecutionHandle {

  final EnvironmentId environmentId;
  final UUID invocationId;
  final EnvironmentCapabilityExecutionListener listener;
  final Duration terminalDeliveryGrace;
  final AtomicBoolean terminal = new AtomicBoolean();
  final AtomicBoolean exitConfirmed = new AtomicBoolean();
  final String daemonInstanceId;
  private final Consumer<ActiveInvocation> onTerminate;
  private final Supplier<DaemonConnectionSession> sessionLookup;
  volatile boolean cancelled;

  /** 最近一次把调用成功递交给 Daemon（初次 INVOKE 或重连后的 RECOVER）的连接代际；0 表示尚未确认递交。 */
  volatile long deliveredGeneration;

  /** 最近一次成功递交 CANCEL 的连接代际；0 表示尚未送达。 */
  volatile long cancelDeliveredGeneration;

  /** 最近一次发起递交 CANCEL 的连接代际；0 表示尚未发起。由 {@code this} 保护。 */
  private long cancelSentGeneration;

  /** 本次调用名下的资源上传绑定（{@code transferId -> binding}），由 {@code this} 保护。 */
  final Map<UUID, TransferBinding> transfers = new LinkedHashMap<>();

  /** 本次 invocation 唯一允许下载的归档（{@code transferId -> blobId}），由 {@code this} 保护。 */
  final Map<UUID, UUID> downloadTransfers = new LinkedHashMap<>();

  ActiveInvocation(
      EnvironmentId environmentId,
      UUID invocationId,
      EnvironmentCapabilityExecutionListener listener,
      Duration terminalDeliveryGrace,
      String daemonInstanceId,
      Consumer<ActiveInvocation> onTerminate,
      Supplier<DaemonConnectionSession> sessionLookup) {
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.terminalDeliveryGrace =
        Objects.requireNonNull(terminalDeliveryGrace, "terminalDeliveryGrace");
    this.daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    this.onTerminate = Objects.requireNonNull(onTerminate, "onTerminate");
    this.sessionLookup = Objects.requireNonNull(sessionLookup, "sessionLookup");
  }

  @Override
  public Duration terminalDeliveryGrace() {
    return terminalDeliveryGrace;
  }

  @Override
  public void terminate(EnvironmentCapabilityTerminationCause cause) {
    Objects.requireNonNull(cause, "cause");
    onTerminate.accept(this);
  }

  @Override
  public void cancel() {
    synchronized (this) {
      cancelled = true;
      if (terminal.get() || cancelDeliveredGeneration > 0) {
        return;
      }
    }
    DaemonConnectionSession session = sessionLookup.get();
    if (session == null) {
      return;
    }
    deliverCancel(session);
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  boolean isTerminal() {
    return terminal.get();
  }

  void markTerminal() {
    synchronized (this) {
      terminal.set(true);
      downloadTransfers.clear();
    }
  }

  boolean claimExit() {
    return exitConfirmed.compareAndSet(false, true);
  }

  void deliverCancel(DaemonConnectionSession offered) {
    long offeredGeneration = offered.generation();
    synchronized (this) {
      if (cancelDeliveredGeneration >= offeredGeneration
          || cancelSentGeneration >= offeredGeneration) {
        return;
      }
      cancelSentGeneration = offeredGeneration;
    }
    Envelope cancelEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.CANCEL)
            .setEnvironmentId(environmentId.value().toString())
            .setInvocationId(invocationId.toString())
            .setCancel(Cancel.getDefaultInstance())
            .build();
    DaemonOfferResult outcome = offered.offerResult(cancelEnvelope);
    if (outcome == DaemonOfferResult.ACCEPTED) {
      DaemonConnectionSession current = sessionLookup.get();
      if (current != null && current.generation() == offeredGeneration) {
        noteCancelDelivered(offeredGeneration);
      }
    }
  }

  void noteCancelDelivered(long generation) {
    synchronized (this) {
      if (generation > cancelDeliveredGeneration) {
        cancelDeliveredGeneration = generation;
      }
      if (generation > cancelSentGeneration) {
        cancelSentGeneration = generation;
      }
    }
  }
}
