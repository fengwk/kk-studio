package fun.fengwk.kkstudio.platform.environment.operation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform 侧异步 Environment Skill 来源管理操作分发器。
 *
 * <p>基于虚拟线程实现无配额分发，负责认领 PENDING 操作、分发至 Daemon 会话核心、协调执行终态与清扫超期操作。 作为 Spring Bean
 * 暴露，但默认不自动启动，由宿主生命周期或测试显式管理 {@link #start()} / {@link #stop()}。
 */
@Slf4j
@Component
public class EnvironmentOperationDispatcher {

  private static final int BATCH_SIZE = 50;
  private static final long POLL_INTERVAL_MS = 2000;

  private final UUID nodeId;
  private final EnvironmentOperationRepository repository;
  private final EnvironmentDaemonGateway gateway;
  private final EnvironmentOperationCompletionCoordinator coordinator;

  private final ConcurrentMap<UUID, EnvironmentCapabilityExecutionHandle> activeHandles =
      new ConcurrentHashMap<>();
  private final Semaphore wakeSignal = new Semaphore(0);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile Thread dispatchThread;

  public EnvironmentOperationDispatcher(
      @Qualifier("nodeInstanceId") UUID nodeId,
      EnvironmentOperationRepository repository,
      EnvironmentDaemonGateway gateway,
      EnvironmentOperationCompletionCoordinator coordinator) {
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  public UUID getNodeId() {
    return nodeId;
  }

  public boolean isRunning() {
    return running.get();
  }

  public int getActiveHandleCount() {
    return activeHandles.size();
  }

  /** 启动后台分发循环与清扫任务。 */
  public synchronized void start() {
    if (running.compareAndSet(false, true)) {
      dispatchThread =
          Thread.ofVirtual().name("env-op-dispatcher-" + nodeId).start(this::runDispatchLoop);
      log.info("EnvironmentOperationDispatcher started for node {}", nodeId);
    }
  }

  /** 停止分发器并取消所有本地活跃句柄。 */
  public synchronized void stop() {
    if (running.compareAndSet(true, false)) {
      wake();
      if (dispatchThread != null) {
        dispatchThread.interrupt();
      }
      for (EnvironmentCapabilityExecutionHandle handle : activeHandles.values()) {
        try {
          handle.cancel();
        } catch (Exception ignored) {
        }
      }
      activeHandles.clear();
      log.info("EnvironmentOperationDispatcher stopped for node {}", nodeId);
    }
  }

  /** 唤醒分发器立即执行一轮认领检查。 */
  public void wake() {
    if (wakeSignal.availablePermits() == 0) {
      wakeSignal.release();
    }
  }

  /** 执行一轮本地与全局超时清扫（可由测试直接调用）。 */
  public void runSweeper() {
    try {
      List<SweptOperationInfo> swept = repository.sweepLocalExpiredRunning(nodeId);
      for (SweptOperationInfo info : swept) {
        EnvironmentCapabilityExecutionHandle handle = activeHandles.remove(info.id());
        if (handle != null) {
          try {
            handle.cancel();
          } catch (Exception ignored) {
          }
        }
      }
      repository.sweepExpiredPending();
      repository.sweepExpiredRunning();
    } catch (Exception e) {
      log.warn("Failed to sweep expired operations");
    }
  }

  private void runDispatchLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        runSweeper();
        if (!running.get()) {
          break;
        }

        List<ClaimedOperation> claimed = repository.claimPendingWithTimeout(nodeId, BATCH_SIZE);
        if (!running.get()) {
          for (ClaimedOperation op : claimed) {
            repository.rescheduleUnsent(op.operation().id(), nodeId, op.operation().leaseToken());
          }
          break;
        }

        if (claimed.isEmpty()) {
          try {
            wakeSignal.tryAcquire(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        } else {
          for (ClaimedOperation op : claimed) {
            if (!running.get()) {
              repository.rescheduleUnsent(op.operation().id(), nodeId, op.operation().leaseToken());
            } else {
              Thread.ofVirtual()
                  .name("env-op-worker-" + op.operation().id())
                  .start(() -> executeOperation(op));
            }
          }
        }
      } catch (Throwable t) {
        if (!running.get() || Thread.currentThread().isInterrupted()) {
          break;
        }
        log.warn("Unexpected error in dispatch loop");
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  private void executeOperation(ClaimedOperation claimedOp) {
    EnvironmentOperation op = claimedOp.operation();
    UUID opId = op.id();
    UUID leaseToken = op.leaseToken();

    // 关机围栏：如果已关机，绝不发起 invoke，直接安全退还为 PENDING
    if (!running.get()) {
      repository.rescheduleUnsent(opId, nodeId, leaseToken);
      return;
    }

    EnvironmentCapabilityId capId = capabilityIdFor(op.operationType());
    EnvironmentCapabilityDescriptor descriptor = EnvironmentCapabilityCatalog.require(capId);
    Duration timeout = claimedOp.remainingTimeout();
    if (descriptor.timeout() != null
        && !descriptor.timeout().isZero()
        && timeout.compareTo(descriptor.timeout()) > 0) {
      timeout = descriptor.timeout();
    }

    EnvironmentCapabilityCall call = new EnvironmentCapabilityCall(opId.toString(), op.arguments());
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout);

    AtomicBoolean terminalHandled = new AtomicBoolean(false);

    EnvironmentCapabilityExecutionListener listener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            // 管理能力不关心 partial 事件
          }

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            if (terminalHandled.compareAndSet(false, true)) {
              activeHandles.remove(opId);
              coordinator.coordinateResult(
                  op.environmentId(),
                  opId,
                  nodeId,
                  leaseToken,
                  op.sourceSetVersion(),
                  op.sourceId(),
                  op.sourceVersion(),
                  result);
            }
          }

          @Override
          public void onError(Throwable error) {
            if (terminalHandled.compareAndSet(false, true)) {
              activeHandles.remove(opId);
              handleExecutionError(op, error);
            }
          }
        };

    try {
      EnvironmentCapabilityExecutionHandle handle =
          gateway.server().invoke(new EnvironmentId(op.environmentId()), request, listener);
      if (!terminalHandled.get() && running.get()) {
        activeHandles.put(opId, handle);
      } else if (!running.get() && !terminalHandled.get()) {
        handle.cancel();
        activeHandles.remove(opId);
      }
    } catch (EnvironmentCapabilityBusyException | EnvironmentCapabilityUnavailableException e) {
      if (terminalHandled.compareAndSet(false, true)) {
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
      }
    } catch (EnvironmentCapabilitySendUncertainException e) {
      terminalHandled.set(true);
      log.warn("Invoke send uncertain before handle exists for operation {}", opId);
    } catch (Throwable t) {
      if (terminalHandled.compareAndSet(false, true)) {
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
      }
    }
  }

  private void handleExecutionError(EnvironmentOperation op, Throwable error) {
    if (error instanceof EnvironmentCapabilitySendUncertainException) {
      coordinator.coordinateUnknown(
          op.id(),
          nodeId,
          op.leaseToken(),
          EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
          EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE);
    } else {
      coordinator.coordinateFailure(
          op.environmentId(),
          op.id(),
          nodeId,
          op.leaseToken(),
          op.sourceSetVersion(),
          op.sourceId(),
          op.sourceVersion(),
          EnvironmentOperationFailureCodes.OPERATION_FAILED,
          EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
    }
  }

  private static EnvironmentCapabilityId capabilityIdFor(EnvironmentOperationType type) {
    return switch (type) {
      case SKILL_REFRESH -> EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH;
      case SKILL_INSTALL -> EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL;
      case SKILL_UPDATE -> EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE;
    };
  }
}
