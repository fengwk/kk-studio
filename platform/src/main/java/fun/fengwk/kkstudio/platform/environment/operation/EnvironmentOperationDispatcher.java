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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
  private final EnvironmentCapabilityTransport transport;
  private final EnvironmentOperationCompletionCoordinator coordinator;
  private final ExecutorService drainExecutor;
  private final ExecutorService workerExecutor;
  private final ScheduledExecutorService pollScheduler;

  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
  private final ConcurrentMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile boolean stopped = false;
  private ScheduledFuture<?> pollFuture;

  public EnvironmentOperationDispatcher(
      @Qualifier("nodeInstanceId") UUID nodeId,
      EnvironmentOperationRepository repository,
      EnvironmentCapabilityTransport transport,
      EnvironmentOperationCompletionCoordinator coordinator,
      @Qualifier("environmentOperationDrainExecutor") ExecutorService drainExecutor,
      @Qualifier("environmentOperationWorkerExecutor") ExecutorService workerExecutor,
      @Qualifier("environmentOperationPollScheduler") ScheduledExecutorService pollScheduler) {
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.drainExecutor = Objects.requireNonNull(drainExecutor, "drainExecutor");
    this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    this.pollScheduler = Objects.requireNonNull(pollScheduler, "pollScheduler");
  }

  public UUID getNodeId() {
    return nodeId;
  }

  public boolean isRunning() {
    return started.get() && !stopped;
  }

  public int getActiveHandleCount() {
    return activeExecutions.size();
  }

  /** 启动后台轮询调度与清扫任务。 */
  public synchronized void start() {
    if (stopped) {
      throw new IllegalStateException("Dispatcher has been permanently stopped");
    }
    if (started.compareAndSet(false, true)) {
      pollFuture =
          pollScheduler.scheduleWithFixedDelay(
              this::pollAndSweep, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
      log.info("EnvironmentOperationDispatcher started for node {}", nodeId);
    }
  }

  /** 永久停止分发器：建立关机围栏，将本节点剩余 RUNNING 记录置为 UNKNOWN，终结监听状态并尽力取消句柄。 */
  public void stop() {
    List<EnvironmentCapabilityExecutionHandle> handlesToCancel = new ArrayList<>();
    lifecycleLock.writeLock().lock();
    try {
      if (stopped) {
        return;
      }
      stopped = true;
      if (pollFuture != null) {
        pollFuture.cancel(true);
      }
      try {
        repository.markRunningUnknownOnShutdown(nodeId);
      } catch (Exception e) {
        log.warn("Failed to mark running operations unknown on shutdown for node {}", nodeId, e);
      }
      for (ActiveExecution execution : activeExecutions.values()) {
        execution.terminal.set(true);
        if (execution.handle != null) {
          handlesToCancel.add(execution.handle);
        }
      }
      activeExecutions.clear();
    } finally {
      lifecycleLock.writeLock().unlock();
    }

    for (EnvironmentCapabilityExecutionHandle handle : handlesToCancel) {
      try {
        handle.cancel();
      } catch (Exception ignored) {
      }
    }
    log.info("EnvironmentOperationDispatcher stopped for node {}", nodeId);
  }

  /** 唤醒分发器立即执行一轮排空检查。 */
  public void wake() {
    if (!stopped) {
      triggerDrain();
    }
  }

  /** 执行一轮本地与全局超时清扫（可由测试或轮询直接调用）。 */
  public void runSweeper() {
    try {
      List<SweptOperationInfo> swept = repository.sweepLocalExpiredRunning(nodeId);
      for (SweptOperationInfo info : swept) {
        ActiveExecution execution = activeExecutions.remove(info.id());
        if (execution != null) {
          execution.terminal.set(true);
          if (execution.handle != null) {
            try {
              execution.handle.cancel();
            } catch (Exception ignored) {
            }
          }
        }
      }
      repository.sweepExpiredPending();
      repository.sweepExpiredRunning();
    } catch (Exception e) {
      log.warn("Failed to sweep expired operations", e);
    }
  }

  private void pollAndSweep() {
    if (stopped) {
      return;
    }
    runSweeper();
    triggerDrain();
  }

  private void triggerDrain() {
    if (stopped) {
      return;
    }
    try {
      drainExecutor.submit(this::drainPendingOperations);
    } catch (Exception e) {
      log.warn("Failed to submit drain task", e);
    }
  }

  private void drainPendingOperations() {
    while (!stopped) {
      List<ClaimedOperation> claimed;
      lifecycleLock.readLock().lock();
      try {
        if (stopped) {
          return;
        }
        claimed = repository.claimPendingWithTimeout(nodeId, BATCH_SIZE);
      } finally {
        lifecycleLock.readLock().unlock();
      }

      if (claimed.isEmpty()) {
        break;
      }

      for (ClaimedOperation op : claimed) {
        lifecycleLock.readLock().lock();
        try {
          if (stopped) {
            repository.rescheduleUnsent(op.operation().id(), nodeId, op.operation().leaseToken());
          } else {
            workerExecutor.submit(() -> executeOperation(op));
          }
        } finally {
          lifecycleLock.readLock().unlock();
        }
      }
    }
  }

  private void executeOperation(ClaimedOperation claimedOp) {
    EnvironmentOperation op = claimedOp.operation();
    UUID opId = op.id();
    UUID leaseToken = op.leaseToken();
    UUID envId = op.environmentId();

    lifecycleLock.readLock().lock();
    try {
      if (stopped) {
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
        return;
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }

    EnvironmentCapabilityExecutionRequest request;
    try {
      EnvironmentCapabilityId capId = capabilityIdFor(op.operationType());
      EnvironmentCapabilityDescriptor descriptor = EnvironmentCapabilityCatalog.require(capId);
      Duration timeout = claimedOp.remainingTimeout();
      if (descriptor.timeout() != null
          && !descriptor.timeout().isZero()
          && timeout.compareTo(descriptor.timeout()) > 0) {
        timeout = descriptor.timeout();
      }
      EnvironmentCapabilityCall call =
          new EnvironmentCapabilityCall(opId.toString(), op.arguments());
      request = new EnvironmentCapabilityExecutionRequest(descriptor, call, timeout);
    } catch (Throwable t) {
      log.warn("Deterministic request construction failure for operation {}", opId, t);
      coordinator.coordinateExecutionFailure(
          envId,
          opId,
          nodeId,
          leaseToken,
          op.sourceSetVersion(),
          op.sourceId(),
          op.sourceVersion());
      return;
    }

    ActiveExecution execution =
        new ActiveExecution(
            opId, envId, leaseToken, op.sourceSetVersion(), op.sourceId(), op.sourceVersion());

    lifecycleLock.readLock().lock();
    try {
      if (stopped) {
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
        return;
      }
      activeExecutions.put(opId, execution);
    } finally {
      lifecycleLock.readLock().unlock();
    }

    EnvironmentCapabilityExecutionListener listener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            // 管理能力不关心 partial 事件
          }

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            if (execution.terminal.compareAndSet(false, true)) {
              activeExecutions.remove(opId);
              coordinator.coordinateResult(
                  envId,
                  opId,
                  nodeId,
                  leaseToken,
                  execution.sourceSetVersion,
                  execution.sourceId,
                  execution.sourceVersion,
                  result);
            }
          }

          @Override
          public void onError(Throwable error) {
            if (execution.terminal.compareAndSet(false, true)) {
              activeExecutions.remove(opId);
              if (error instanceof EnvironmentCapabilitySendUncertainException) {
                coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);
              } else {
                coordinator.coordinateExecutionFailure(
                    envId,
                    opId,
                    nodeId,
                    leaseToken,
                    execution.sourceSetVersion,
                    execution.sourceId,
                    execution.sourceVersion);
              }
            }
          }
        };

    EnvironmentCapabilityExecutionHandle handle;
    try {
      handle = transport.invoke(new EnvironmentId(envId), request, listener);
    } catch (EnvironmentCapabilityBusyException | EnvironmentCapabilityUnavailableException e) {
      if (execution.terminal.compareAndSet(false, true)) {
        activeExecutions.remove(opId);
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
      }
      return;
    } catch (EnvironmentCapabilitySendUncertainException e) {
      if (execution.terminal.compareAndSet(false, true)) {
        activeExecutions.remove(opId);
      }
      return;
    } catch (Throwable t) {
      if (execution.terminal.compareAndSet(false, true)) {
        activeExecutions.remove(opId);
        coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);
      }
      return;
    }

    execution.handle = handle;
    if (execution.terminal.get() || stopped) {
      try {
        handle.cancel();
      } catch (Exception ignored) {
      }
    }
  }

  private static EnvironmentCapabilityId capabilityIdFor(EnvironmentOperationType type) {
    return switch (type) {
      case SKILL_REFRESH -> EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH;
      case SKILL_INSTALL -> EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL;
      case SKILL_UPDATE -> EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE;
    };
  }

  static final class ActiveExecution {
    final UUID operationId;
    final UUID environmentId;
    final UUID leaseToken;
    final long sourceSetVersion;
    final UUID sourceId;
    final long sourceVersion;
    final AtomicBoolean terminal = new AtomicBoolean(false);
    volatile EnvironmentCapabilityExecutionHandle handle;

    ActiveExecution(
        UUID operationId,
        UUID environmentId,
        UUID leaseToken,
        long sourceSetVersion,
        UUID sourceId,
        long sourceVersion) {
      this.operationId = operationId;
      this.environmentId = environmentId;
      this.leaseToken = leaseToken;
      this.sourceSetVersion = sourceSetVersion;
      this.sourceId = sourceId;
      this.sourceVersion = sourceVersion;
    }
  }
}
