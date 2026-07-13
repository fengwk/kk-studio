package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** EmbeddedAgentRunRuntimeService 的调度失败行为测试。 */
public class EmbeddedAgentRunRuntimeServiceTest {

  /** executor 拒绝任务时，已创建的 queued run 必须立即进入 failed。 */
  @Test
  public void shouldFailQueuedRunWhenExecutorRejectsTask() {
    AgentRunService agentRunService = mock(AgentRunService.class);
    RejectedExecutionException rejection = new RejectedExecutionException("executor closed");
    Executor rejectingExecutor =
        command -> {
          throw rejection;
        };
    when(agentRunService.markFailed("rn_rejected")).thenReturn(true);
    EmbeddedAgentRunRuntimeService runtimeService =
        new EmbeddedAgentRunRuntimeService(agentRunService, null, rejectingExecutor);

    RejectedExecutionException thrown =
        assertThrows(
            RejectedExecutionException.class,
            () -> runtimeService.scheduleQueuedRun("rn_rejected", "se_1", "hello"));

    assertSame(rejection, thrown);
    verify(agentRunService).markFailed("rn_rejected");
  }

  /** 已被其它执行器领取或关闭的 run 不应再次加载 Agent。 */
  @Test
  public void shouldIgnoreRunWhenMarkRunningFails() {
    AgentRunService agentRunService = mock(AgentRunService.class);
    EmbeddedAgentRuntimeLoader runtimeLoader = mock(EmbeddedAgentRuntimeLoader.class);
    when(agentRunService.markRunning("rn_closed")).thenReturn(false);
    EmbeddedAgentRunRuntimeService runtimeService =
        new EmbeddedAgentRunRuntimeService(agentRunService, runtimeLoader, Runnable::run);

    runtimeService.scheduleQueuedRun("rn_closed", "se_1", "hello");

    verifyNoInteractions(runtimeLoader);
  }

  /** run 已被并发关闭时仍应把 executor 的拒绝异常原样返回。 */
  @Test
  public void shouldPropagateRejectionWhenQueuedRunIsAlreadyClosed() {
    AgentRunService agentRunService = mock(AgentRunService.class);
    RejectedExecutionException rejection = new RejectedExecutionException("executor closed");
    Executor rejectingExecutor =
        command -> {
          throw rejection;
        };
    when(agentRunService.markFailed("rn_closed")).thenReturn(false);
    EmbeddedAgentRunRuntimeService runtimeService =
        new EmbeddedAgentRunRuntimeService(agentRunService, null, rejectingExecutor);

    RejectedExecutionException thrown =
        assertThrows(
            RejectedExecutionException.class,
            () -> runtimeService.scheduleQueuedRun("rn_closed", "se_1", "hello"));

    assertSame(rejection, thrown);
    verify(agentRunService).markFailed("rn_closed");
  }
}
