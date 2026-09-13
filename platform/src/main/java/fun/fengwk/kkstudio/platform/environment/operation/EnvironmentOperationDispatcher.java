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

  public static final String CHANNEL = "environment_operation_pending";

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
  private final AtomicBoolean wakeRequested = new AtomicBoolean(false);
  private final AtomicBoolean drainRunning = new AtomicBoolean(false);

  private volatile boolean started = false;
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
    return started && !stopped;
  }

  public int getActiveHandleCount() {
    return activeExecutions.size();
  }

  /** 启动后台轮询调度与清扫任务。 */
  public void start() {
    lifecycleLock.writeLock().lock();
    try {
      if (stopped) {
        throw new IllegalStateException("Dispatcher has been permanently stopped");
      }
      if (started) {
        return;
      }
      try {
        pollFuture =
            pollScheduler.scheduleWithFixedDelay(
                this::pollAndSweep, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
      } catch (RuntimeException e) {
        log.warn("Failed to schedule poll task for node {}", nodeId);
        throw e;
      }
      started = true;
      log.info("EnvironmentOperationDispatcher started for node {}", nodeId);
    } finally {
      lifecycleLock.writeLock().unlock();
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
        pollFuture = null;
      }
      try {
        repository.markRunningUnknownOnShutdown(nodeId);
      } catch (RuntimeException e) {
        log.warn("Failed to mark running operations unknown on shutdown for node {}", nodeId);
      }
      for (ActiveExecution execution : activeExecutions.values()) {
        execution.terminal.set(true);
        execution.abandoned = true;
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
      } catch (RuntimeException ignored) {
      }
    }
    log.info("EnvironmentOperationDispatcher stopped for node {}", nodeId);
  }

  /** 唤醒分发器立即执行一轮排空检查。start 前或 stop 后为 no-op；并发 wake 合并为单一 drain。 */
  public void wake() {
    if (!isRunning()) {
      return;
    }
    wakeRequested.set(true);
    if (drainRunning.compareAndSet(false, true)) {
      submitDrain();
    }
  }

  /** 执行一轮本地与全局超时清扫（可由测试或轮询直接调用）。 */
  public void runSweeper() {
    List<EnvironmentCapabilityExecutionHandle> handlesToCancel = new ArrayList<>();
    try {
      List<SweptOperationInfo> swept = repository.sweepLocalExpiredRunning(nodeId);
      for (SweptOperationInfo info : swept) {
        ActiveExecution execution = activeExecutions.get(info.id());
        if (execution != null && execution.terminal.compareAndSet(false, true)) {
          activeExecutions.remove(info.id());
          execution.abandoned = true;
          if (execution.handle != null) {
            handlesToCancel.add(execution.handle);
          }
        }
      }
      repository.sweepExpiredPending();
      repository.sweepExpiredRunning();
    } catch (RuntimeException e) {
      log.warn("Failed to sweep expired operations for node {}", nodeId);
    }
    for (EnvironmentCapabilityExecutionHandle handle : handlesToCancel) {
      try {
        handle.cancel();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void pollAndSweep() {
    if (!isRunning()) {
      return;
    }
    runSweeper();
    wake();
  }

  private void submitDrain() {
    if (!isRunning()) {
      drainRunning.set(false);
      return;
    }
    try {
      drainExecutor.execute(this::runDrain);
    } catch (RuntimeException error) {
      drainRunning.set(false);
      log.warn("Drain executor rejected task for node {}", nodeId);
    }
  }

  private void runDrain() {
    boolean workerRejected = false;
    try {
      do {
        wakeRequested.set(false);
        if (!drainPendingOperations()) {
          workerRejected = true;
          break;
        }
      } while (isRunning() && wakeRequested.get());
    } catch (RuntimeException error) {
      log.warn("Drain loop failed for node {}", nodeId);
    } finally {
      drainRunning.set(false);
      if (!workerRejected
          && isRunning()
          && wakeRequested.get()
          && drainRunning.compareAndSet(false, true)) {
        submitDrain();
      }
    }
  }

  private boolean drainPendingOperations() {
    while (isRunning()) {
      List<ClaimedOperation> claimed;
      lifecycleLock.readLock().lock();
      try {
        if (!isRunning()) {
          return true;
        }
        claimed = repository.claimPendingWithTimeout(nodeId, BATCH_SIZE);
      } finally {
        lifecycleLock.readLock().unlock();
      }

      if (claimed.isEmpty()) {
        break;
      }

      for (int i = 0; i < claimed.size(); i++) {
        ClaimedOperation op = claimed.get(i);
        lifecycleLock.readLock().lock();
        try {
          if (!isRunning()) {
            rescheduleRemaining(claimed, i);
            return true;
          }
          try {
            workerExecutor.submit(() -> executeOperation(op));
          } catch (RuntimeException error) {
            log.warn(
                "Worker executor rejected operation {} for node {}", op.operation().id(), nodeId);
            rescheduleRemaining(claimed, i);
            return false;
          }
        } finally {
          lifecycleLock.readLock().unlock();
        }
      }
    }
    return true;
  }

  private void rescheduleRemaining(List<ClaimedOperation> claimed, int startIndex) {
    for (int j = startIndex; j < claimed.size(); j++) {
      ClaimedOperation remaining = claimed.get(j);
      try {
        repository.rescheduleUnsent(
            remaining.operation().id(), nodeId, remaining.operation().leaseToken());
      } catch (RuntimeException e) {
        log.warn(
            "Failed to reschedule operation {} for node {}", remaining.operation().id(), nodeId);
      }
    }
  }

  private void executeOperation(ClaimedOperation claimedOp) {
    EnvironmentOperation op = claimedOp.operation();
    UUID opId = op.id();
    UUID leaseToken = op.leaseToken();
    UUID envId = op.environmentId();

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
    } catch (RuntimeException e) {
      log.warn("Deterministic request construction failure for operation {}", opId);
      coordinator.coordinateExecutionFailure(
          envId,
          opId,
          nodeId,
          leaseToken,
          op.operationType(),
          op.resourceType(),
          op.resourceId(),
          op.resourceVersion(),
          op.arguments());
      return;
    }

    ActiveExecution execution =
        new ActiveExecution(
            opId,
            envId,
            leaseToken,
            op.operationType(),
            op.resourceType(),
            op.resourceId(),
            op.resourceVersion(),
            op.arguments());

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
                  execution.operationType,
                  execution.resourceType,
                  execution.resourceId,
                  execution.resourceVersion,
                  execution.arguments,
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
                    execution.operationType,
                    execution.resourceType,
                    execution.resourceId,
                    execution.resourceVersion,
                    execution.arguments);
              }
            }
          }
        };

    EnvironmentCapabilityExecutionHandle handle;
    lifecycleLock.readLock().lock();
    try {
      if (!isRunning()) {
        repository.rescheduleUnsent(opId, nodeId, leaseToken);
        return;
      }
      activeExecutions.put(opId, execution);
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
          coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);
        }
        return;
      } catch (RuntimeException e) {
        if (execution.terminal.compareAndSet(false, true)) {
          activeExecutions.remove(opId);
          coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);
        }
        return;
      }

      if (handle == null) {
        if (execution.terminal.compareAndSet(false, true)) {
          activeExecutions.remove(opId);
          coordinator.coordinateTransportUnknown(opId, nodeId, leaseToken);
        } else {
          activeExecutions.remove(opId);
        }
        return;
      }

      execution.handle = handle;
      if (execution.terminal.get()) {
        activeExecutions.remove(opId);
        if (execution.abandoned) {
          try {
            handle.cancel();
          } catch (RuntimeException ignored) {
          }
        }
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  private static EnvironmentCapabilityId capabilityIdFor(EnvironmentOperationType type) {
    return switch (type) {
      case SKILL_REFRESH -> EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH;
      case SKILL_INSTALL -> EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL;
      case SKILL_UPDATE -> EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE;
      case MCP_SERVER_DISCOVER -> new EnvironmentCapabilityId("mcp.local.discover");
    };
  }

  static final class ActiveExecution {
    final UUID operationId;
    final UUID environmentId;
    final UUID leaseToken;
    final EnvironmentOperationType operationType;
    final EnvironmentOperationResourceType resourceType;
    final UUID resourceId;
    final long resourceVersion;
    final String arguments;
    final AtomicBoolean terminal = new AtomicBoolean(false);
    volatile boolean abandoned = false;
    volatile EnvironmentCapabilityExecutionHandle handle;

    ActiveExecution(
        UUID operationId,
        UUID environmentId,
        UUID leaseToken,
        EnvironmentOperationType operationType,
        EnvironmentOperationResourceType resourceType,
        UUID resourceId,
        long resourceVersion,
        String arguments) {
      this.operationId = operationId;
      this.environmentId = environmentId;
      this.leaseToken = leaseToken;
      this.operationType = operationType;
      this.resourceType = resourceType;
      this.resourceId = resourceId;
      this.resourceVersion = resourceVersion;
      this.arguments = arguments;
    }
  }
}
