package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.core.testing.StubProviderManager;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 验证 assistant event 在 run 还未结束时就对外可见（流式恢复）。
 *
 * <p>关键断言：
 *
 * <ul>
 *   <li>createMessage 返回时只包含 user_message，assistant event 还没出现
 *   <li>scheduleQueuedRun 之后，在 run status 还是 running 期间就能读到 assistant_start 之类
 *   <li>释放 stub 后 run 走完，所有 event 都到位
 * </ul>
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentRunStreamingTest {

  @Autowired private AgentSessionService agentSessionService;

  @Autowired private AgentRunService agentRunService;

  @Autowired private AgentRunRuntimeService agentRunRuntimeService;

  @Autowired private StubProviderManager stubProviderManager;

  @Test
  public void shouldMakeAssistantEventsVisibleWhileRunIsStillRunning() throws InterruptedException {
    // 用 pausable stub：发出第一个 delta 后阻塞在 releaseLatch 上，
    // 让 run 停在 running 状态直到测试主动释放。
    StubProviderManager.PausableScript stub =
        stubProviderManager.enqueuePausableText("Hello streaming world");
    ExecutorService executorService =
        Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "agent-run-streaming-test"));

    AgentSessionDTO session = createSession();
    AgentSessionMessageCreateDTO createDTO = new AgentSessionMessageCreateDTO();
    createDTO.setContent("please stream");

    // 1) createMessage 立刻返回，只看到 user_message
    AgentSessionEventDTO createdEvent =
        agentSessionService.createMessage(session.getSessionId(), createDTO);
    List<AgentSessionEventDTO> eventsAfterCreate =
        agentSessionService.listEvents(session.getSessionId(), null);
    assertEquals(
        1,
        eventsAfterCreate.size(),
        "right after createMessage, only user_message should be visible");
    assertEquals("user_message", eventsAfterCreate.get(0).getEventType());

    Future<?> runFuture =
        executorService.submit(
            () ->
                agentRunRuntimeService.scheduleQueuedRun(
                    createdEvent.getRunId(), session.getSessionId(), createDTO.getContent()));
    try {
      // 2) 调度 run，并在独立线程里执行，避免测试配置里的同步 executor 把当前断言卡住。

      // 3) 等待 status=running 且事件数 > 1（说明 assistant_start 已经落库）。
      // 这就是流式恢复的核心断言：assistant_* event 在 run 还没结束时就已经可见，
      // 不是等 run 结束后才一次性 commit。
      long deadline = System.currentTimeMillis() + 5000L;
      int observedEvents = 1;
      while (System.currentTimeMillis() < deadline) {
        AgentRunDTO run = agentRunService.getRun(createdEvent.getRunId());
        List<AgentSessionEventDTO> events =
            agentSessionService.listEvents(session.getSessionId(), null);
        observedEvents = Math.max(observedEvents, events.size());
        boolean running = run != null && "running".equals(run.getStatus());
        if (running && events.size() > 1) {
          break;
        }
        Thread.sleep(5L);
      }
      assertTrue(
          observedEvents > 1,
          "events must grow beyond the single user_message while the run is still running, "
              + "observed max="
              + observedEvents
              + " (this means assistant events are still "
              + "trapped behind an outer transaction)");

      // 4) 释放 stub，让 run 走完，并显式要求任务在线程内及时收敛到 succeeded。
      stub.release();
      try {
        runFuture.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        fail("scheduled run should finish soon after releasing the stub", e);
      } catch (ExecutionException e) {
        fail("scheduled run thread should complete without throwing", e);
      }

      AgentRunDTO finalRun = agentRunService.getRun(createdEvent.getRunId());
      assertTrue(finalRun != null && "succeeded".equals(finalRun.getStatus()));

      List<AgentSessionEventDTO> finalEvents =
          agentSessionService.listEvents(session.getSessionId(), null);
      assertTrue(
          finalEvents.size() >= 3,
          "after completion we should see at least user_message + assistant_start + assistant_end, got: "
              + finalEvents.size());
    } finally {
      stub.release();
      runFuture.cancel(true);
      executorService.shutdownNow();
    }
  }

  private AgentSessionDTO createSession() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("streaming-test-" + System.nanoTime());
    return agentSessionService.createSession(createDTO);
  }
}
