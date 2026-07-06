package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
@Slf4j
public class EmbeddedAgentRunRuntimeService implements AgentRunRuntimeService {

  private static final Duration RUN_IDLE_TIMEOUT = Duration.ofMinutes(3);
  private static final long RUN_IDLE_POLL_MILLIS = 50L;

  private final AgentRunService agentRunService;
  private final EmbeddedAgentRuntimeLoader runtimeLoader;

  @Qualifier("agentRunTaskExecutor")
  private final Executor agentRunTaskExecutor;

  @Override
  public void scheduleQueuedRun(String runId, String sessionId, String content) {
    agentRunTaskExecutor.execute(() -> executeQueuedRun(runId, sessionId, content));
  }

  @Override
  public void executeQueuedRun(String runId, String sessionId, String content) {
    // 不再包外层事务：agent 主循环内的每条 event（assistant_delta 等）
    // 都是单条 INSERT，由 MyBatis/auto-commit 立即对外可见，
    // 这样 SSE 端在下个 250ms 轮询周期就能拿到新的 delta，而不是等 run 结束才一次性 commit。
    // event 之间没有原子要求，run 状态机由 agentRunService 单条 UPDATE 维护，
    // 崩溃遗留的 stale run 由 StaleRunReconciler 启动时清理。
    runOnce(runId, sessionId, content);
  }

  private void runOnce(String runId, String sessionId, String content) {
    if (!agentRunService.markRunning(runId)) {
      return;
    }

    try {
      RecordingAgentEventHandler eventHandler = new RecordingAgentEventHandler();
      Agent agent = runtimeLoader.load(runId, sessionId, eventHandler);
      agent.submit(UserRequest.userRequest(content));
      if (waitForAgentIdle(agent)) {
        if (eventHandler.isTerminalFailure()) {
          agentRunService.markFailed(runId);
        } else {
          agentRunService.markSucceeded(runId);
        }
      } else {
        log.warn("Execute queued run timed out, runId: {}, sessionId: {}", runId, sessionId);
        agentRunService.markFailed(runId);
      }
    } catch (Exception e) {
      log.error("Execute queued run failed, runId: {}, sessionId: {}", runId, sessionId, e);
      agentRunService.markFailed(runId);
    }
  }

  private boolean waitForAgentIdle(Agent agent) {
    long deadline = System.nanoTime() + RUN_IDLE_TIMEOUT.toNanos();
    while (agent.getStatus() != AgentStatus.idle) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(RUN_IDLE_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }
}
