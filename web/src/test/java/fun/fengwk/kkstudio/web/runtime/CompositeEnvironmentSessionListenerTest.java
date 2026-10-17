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
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.web.environment.ide.IdeLeaseCloseListener;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 意图：验证 compositeEnvironmentSessionListener 在 READY 事件上既唤醒 HarnessWorkDispatcher，又触发 Skill
 * 全量同步与受管更新 最终成功判定，且任一宿主抛出运行时异常时都不得向调用方泄露异常。
 */
class CompositeEnvironmentSessionListenerTest {

  private HarnessWorkDispatcher workDispatcher;
  private ObjectProvider<HarnessWorkDispatcher> dispatcherProvider;
  private EnvironmentSkillSyncOrchestrator orchestrator;
  private ObjectProvider<EnvironmentSkillSyncOrchestrator> orchestratorProvider;
  private EnvironmentUpdateService updateService;
  private ObjectProvider<EnvironmentUpdateService> updateServiceProvider;
  private IdeLeaseCloseListener leaseCloseListener;
  private ObjectProvider<IdeLeaseCloseListener> leaseCloseProvider;
  private EnvironmentSessionListener listener;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    workDispatcher = mock(HarnessWorkDispatcher.class);
    orchestrator = mock(EnvironmentSkillSyncOrchestrator.class);
    updateService = mock(EnvironmentUpdateService.class);
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

    updateServiceProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentUpdateService> consumer = invocation.getArgument(0);
              consumer.accept(updateService);
              return null;
            })
        .when(updateServiceProvider)
        .ifAvailable(any());

    leaseCloseListener = mock(IdeLeaseCloseListener.class);
    leaseCloseProvider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<IdeLeaseCloseListener> consumer = invocation.getArgument(0);
              consumer.accept(leaseCloseListener);
              return null;
            })
        .when(leaseCloseProvider)
        .ifAvailable(any());

    listener =
        config.compositeEnvironmentSessionListener(
            dispatcherProvider, orchestratorProvider, updateServiceProvider, leaseCloseProvider);
  }

  @Test
  void forwardsLeaseCloseToIdeDataPlaneCleanup() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    UUID leaseToken = UUID.randomUUID();

    listener.onEnvironmentDisconnected(envId, leaseToken);

    verify(leaseCloseListener).onLeaseClosed(envId, leaseToken);
  }

  @Test
  void wakesWorkDispatcherAndTriggersSkillSync() {
    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(workDispatcher).wake();
    verify(orchestrator).onEnvironmentReady(envId);
    verify(updateService).onEnvironmentReady(envId);
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

  @Test
  void updateServiceExceptionDoesNotPropagate() {
    doThrow(new IllegalStateException("update confirmation failed"))
        .when(updateService)
        .onEnvironmentReady(any());

    EnvironmentId envId = EnvironmentId.of(UUID.randomUUID());
    assertDoesNotThrow(() -> listener.onEnvironmentReady(envId));

    verify(updateService).onEnvironmentReady(envId);
  }
}
