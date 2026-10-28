package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import fun.fengwk.kkstudio.ide.IdeLeaseCoordinator;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.web.environment.ide.IdeGenerationCloseListener;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 意图：验证 compositeEnvironmentSessionListener 在 READY 事件上既唤醒 HarnessWorkDispatcher，又触发 Skill
 * 全量同步；Daemon 实例接管时转发给 IDE 数据面清理；任一宿主抛出运行时异常时都不得向调用方泄露异常。
 */
class CompositeEnvironmentSessionListenerTest {

  private HarnessWorkDispatcher workDispatcher;
  private ObjectProvider<HarnessWorkDispatcher> dispatcherProvider;
  private EnvironmentSkillSyncOrchestrator orchestrator;
  private ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider;
  private IdeGenerationCloseListener generationCloseListener;
  private ObjectProvider<IdeGenerationCloseListener> generationCloseProvider;
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

    HarnessRuntimeConfiguration config = new HarnessRuntimeConfiguration();
    orchestratorProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentSkillSyncOrchestrator> consumer = invocation.getArgument(0);
              consumer.accept(orchestrator);
              return null;
            })
        .when(orchestratorProvider)
        .ifAvailable(any());

    generationCloseListener = mock(IdeGenerationCloseListener.class);
    generationCloseProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<IdeGenerationCloseListener> consumer = invocation.getArgument(0);
              consumer.accept(generationCloseListener);
              return null;
            })
        .when(generationCloseProvider)
        .ifAvailable(any());

    ObjectProvider<IdeLeaseCoordinator> leaseCoordinatorProvider = mock(ObjectProvider.class);
    listener =
        config.compositeEnvironmentSessionListener(
            dispatcherProvider,
            orchestratorProvider,
            generationCloseProvider,
            leaseCoordinatorProvider);
  }

  @Test
  void forwardsInstanceReplacementToIdeDataPlaneCleanup() {
    DaemonConnectionIdentity previous =
        new DaemonConnectionIdentity(EnvironmentId.of(UUID.randomUUID()), "daemon-1");

    listener.onDaemonInstanceReplaced(previous);

    verify(generationCloseListener).onGenerationClosed(previous);
  }

  @Test
  void ideCleanupExceptionDoesNotPropagate() {
    DaemonConnectionIdentity previous =
        new DaemonConnectionIdentity(EnvironmentId.of(UUID.randomUUID()), "daemon-1");
    doThrow(new IllegalStateException("ide cleanup failed"))
        .when(generationCloseListener)
        .onGenerationClosed(any());

    assertDoesNotThrow(() -> listener.onDaemonInstanceReplaced(previous));
  }

  @Test
  void wakesWorkDispatcherAndTriggersSkillSync() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(workDispatcher).wake();
    verify(orchestrator).onEnvironmentReady(envId);
  }

  @Test
  void workDispatcherExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("work wake failed")).when(workDispatcher).wake();

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(workDispatcher).wake();
    verify(orchestrator).onEnvironmentReady(envId);
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
