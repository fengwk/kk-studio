package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.execution.RemoteExitReconciler;
import fun.fengwk.kkstudio.ide.IdeLeaseCoordinator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * 意图：验证 compositeEnvironmentSessionListener 在 READY 事件上既唤醒 HarnessWorkDispatcher，又触发 Skill
 * 全量同步；任一宿主抛出运行时异常时都不得向调用方泄露异常。
 */
class CompositeEnvironmentSessionListenerTest {

  private HarnessWorkDispatcher workDispatcher;
  private ObjectProvider<HarnessWorkDispatcher> dispatcherProvider;
  private EnvironmentSkillSyncOrchestrator orchestrator;
  private ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider;
  private IdeLeaseCoordinator leaseCoordinator;
  private ObjectProvider<IdeLeaseCoordinator> leaseCoordinatorProvider;
  private RemoteExitReconciler reconciler;
  private ObjectProvider<RemoteExitReconciler> reconcilerProvider;
  private EnvironmentSessionListener listener;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    workDispatcher = mock(HarnessWorkDispatcher.class);
    orchestrator = mock(EnvironmentSkillSyncOrchestrator.class);
    dispatcherProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<HarnessWorkDispatcher> consumer = invocation.getArgument(0);
              consumer.accept(workDispatcher);
              return null;
            })
        .when(dispatcherProvider)
        .ifAvailable(any());

    HarnessEnvironmentConfiguration config = new HarnessEnvironmentConfiguration();
    orchestratorProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentSkillSyncOrchestrator> consumer = invocation.getArgument(0);
              consumer.accept(orchestrator);
              return null;
            })
        .when(orchestratorProvider)
        .ifAvailable(any());

    leaseCoordinator = mock(IdeLeaseCoordinator.class);
    leaseCoordinatorProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<IdeLeaseCoordinator> consumer = invocation.getArgument(0);
              consumer.accept(leaseCoordinator);
              return null;
            })
        .when(leaseCoordinatorProvider)
        .ifAvailable(any());

    reconciler = mock(RemoteExitReconciler.class);
    reconcilerProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<RemoteExitReconciler> consumer = invocation.getArgument(0);
              consumer.accept(reconciler);
              return null;
            })
        .when(reconcilerProvider)
        .ifAvailable(any());

    listener =
        config.compositeEnvironmentSessionListener(
            dispatcherProvider, orchestratorProvider, leaseCoordinatorProvider, reconcilerProvider);
  }

  /** 队列按每个并发槽 8 个排队任务计算，容量用尽时 AbortPolicy 拒绝。 */
  @Test
  void skillSyncExecutorQueuesEightTasksPerSlot() {
    HarnessExecutionAdmissionProperties properties = new HarnessExecutionAdmissionProperties();
    properties.setSkillSync(2);
    ExecutorService executor =
        new HarnessEnvironmentConfiguration().environmentSkillSyncExecutor(properties);
    try {
      ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor);
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(2, pool.getMaximumPoolSize());
      assertInstanceOf(LinkedBlockingQueue.class, pool.getQueue());
      assertEquals(16, pool.getQueue().remainingCapacity());
      assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void remoteExitReconcileExecutorCreatesVirtualThreadExecutor() {
    ExecutorService executor = new HarnessEnvironmentConfiguration().remoteExitReconcileExecutor();
    try {
      assertNotNull(executor);
      assertFalse(executor.isShutdown());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void wakesWorkDispatcherAndTriggersSkillSync() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(workDispatcher).wake();
    verify(orchestrator).onEnvironmentReady(envId);
    verify(reconciler).onEnvironmentReady(envId);
  }

  @Test
  void workDispatcherExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("work wake failed")).when(workDispatcher).wake();

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(workDispatcher).wake();
    verify(orchestrator).onEnvironmentReady(envId);
    verify(reconciler).onEnvironmentReady(envId);
  }

  @Test
  void exitReconciliationExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("exit reconcile failed"))
        .when(reconciler)
        .onEnvironmentReady(any());

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(reconciler).onEnvironmentReady(envId);
  }

  @Test
  void forwardsHeartbeatToLeaseCoordinator() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(envId, "daemon-1");

    listener.onHeartbeat(envId, identity, 7L);

    verify(leaseCoordinator).onHeartbeat(envId, identity, 7L);
  }

  @Test
  void heartbeatExceptionDoesNotPropagate() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(envId, "daemon-1");
    doThrow(new IllegalStateException("lease heartbeat failed"))
        .when(leaseCoordinator)
        .onHeartbeat(any(), any(), any());

    assertDoesNotThrow(() -> listener.onHeartbeat(envId, identity, 7L));
  }

  @Test
  void skillSyncExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("skill sync failed"))
        .when(orchestrator)
        .onEnvironmentReady(any());

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(orchestrator).onEnvironmentReady(envId);
  }
}
