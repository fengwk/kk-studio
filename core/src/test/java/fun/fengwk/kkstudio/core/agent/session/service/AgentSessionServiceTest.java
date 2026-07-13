package fun.fengwk.kkstudio.core.agent.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.testing.StubProviderManager;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentSessionServiceTest {

  @Autowired private AgentSessionService agentSessionService;

  @Autowired private AgentRunService agentRunService;

  @Autowired private AgentRunRuntimeService agentRunRuntimeService;

  @Autowired private AgentSessionEventRepository agentSessionEventRepository;

  @Autowired private AgentSessionRepository agentSessionRepository;

  @Autowired private StubProviderManager stubProviderManager;

  private static final Set<String> TERMINAL_STATUSES = Set.of("succeeded", "failed");

  private void waitForRunCompleted(String runId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000L;
    while (System.currentTimeMillis() < deadline) {
      AgentRunDTO run = agentRunService.getRun(runId);
      if (run != null && TERMINAL_STATUSES.contains(run.getStatus())) {
        return;
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("run did not reach terminal status within 5s: " + runId);
  }

  @BeforeEach
  public void resetStubProviderManager() {
    stubProviderManager.reset();
  }

  @Test
  public void shouldPageSessionsByLatestUpdateTime() {
    Page<AgentSessionDTO> baselineSessions =
        agentSessionService.pageSessions(new PageQuery(1, 100));

    AgentSessionCreateDTO firstCreateDTO = new AgentSessionCreateDTO();
    firstCreateDTO.setAgentName("default-assistant");
    firstCreateDTO.setTitle("First Session");
    AgentSessionDTO firstSession = agentSessionService.createSession(firstCreateDTO);

    AgentSessionCreateDTO secondCreateDTO = new AgentSessionCreateDTO();
    secondCreateDTO.setAgentName("default-assistant");
    secondCreateDTO.setTitle("Second Session");
    AgentSessionDTO secondSession = agentSessionService.createSession(secondCreateDTO);

    Page<AgentSessionDTO> sessions = agentSessionService.pageSessions(new PageQuery(1, 100));
    assertEquals(baselineSessions.getTotalCount() + 2, sessions.getTotalCount());
    int firstSessionIndex = indexOfSession(sessions.getResults(), firstSession.getSessionId());
    int secondSessionIndex = indexOfSession(sessions.getResults(), secondSession.getSessionId());
    assertTrue(firstSessionIndex >= 0);
    assertTrue(secondSessionIndex >= 0);
    assertTrue(secondSessionIndex < firstSessionIndex);
  }

  @Test
  public void shouldCreateAndLoadSessionMetadata() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Bootstrap Session");

    AgentSessionDTO created = agentSessionService.createSession(createDTO);
    assertNotNull(created);
    assertTrue(created.getSessionId().startsWith("se_"));
    assertEquals("default-assistant", created.getAgentName());
    assertEquals("Bootstrap Session", created.getTitle());
    assertNotNull(created.getCreateTime());
    assertNotNull(created.getUpdateTime());

    AgentSessionDTO loaded = agentSessionService.getSession(created.getSessionId());
    assertEquals(created.getSessionId(), loaded.getSessionId());
    assertEquals(created.getAgentName(), loaded.getAgentName());
    assertEquals(created.getTitle(), loaded.getTitle());
    assertEquals(
        "root",
        agentSessionRepository.getBySessionId(created.getSessionId()).getCurrentHeadEventId());

    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(created.getSessionId(), null);
    assertTrue(events.isEmpty());
  }

  @Test
  public void shouldNormalizeSessionTitleWhenUpdatingSession() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Original Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionUpdateDTO renameDTO = new AgentSessionUpdateDTO();
    renameDTO.setTitle("  Renamed Session  ");
    AgentSessionUpdateDTO clearDTO = new AgentSessionUpdateDTO();
    clearDTO.setTitle("   ");

    // 更新链路负责裁剪标题，并允许通过空白标题清空展示名。
    AgentSessionDTO renamedSession =
        agentSessionService.updateSession("  " + session.getSessionId() + "  ", renameDTO);
    assertEquals("Renamed Session", renamedSession.getTitle());

    AgentSessionDTO clearedSession =
        agentSessionService.updateSession(session.getSessionId(), clearDTO);
    assertNull(clearedSession.getTitle());
    assertNull(agentSessionService.getSession(session.getSessionId()).getTitle());
  }

  /** 删除会话必须清理整个持久化聚合，而不是遗留不可访问的 event 和 run。 */
  @Test
  public void shouldDeleteCompletedSessionAggregate() throws InterruptedException {
    stubProviderManager.enqueueText("delete me");
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Disposable Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO message = new AgentSessionMessageCreateDTO();
    message.setContent("complete before delete");
    AgentSessionEventDTO event = agentSessionService.createMessage(session.getSessionId(), message);
    agentRunRuntimeService.scheduleQueuedRun(
        event.getRunId(), session.getSessionId(), message.getContent());
    waitForRunCompleted(event.getRunId());

    agentSessionService.deleteSession(session.getSessionId());

    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.getSession(session.getSessionId()));
    assertTrue(agentSessionEventRepository.listBySessionId(session.getSessionId()).isEmpty());
    assertNull(agentRunService.getRun(event.getRunId()));
  }

  /** 活跃 run 仍可能写事件，此时删除 session 必须被拒绝。 */
  @Test
  public void shouldRejectDeletingSessionWithActiveRun() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Active Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO message = new AgentSessionMessageCreateDTO();
    message.setContent("still queued");
    agentSessionService.createMessage(session.getSessionId(), message);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> agentSessionService.deleteSession(session.getSessionId()));

    assertEquals("session has active run: " + session.getSessionId(), error.getMessage());
    assertNotNull(agentSessionService.getSession(session.getSessionId()));
    assertEquals(1, agentSessionEventRepository.listBySessionId(session.getSessionId()).size());
  }

  @Test
  public void shouldAppendUserMessageAndAdvanceSessionHead() throws InterruptedException {
    stubProviderManager.enqueueText("Agent reply");

    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Message Session");

    AgentSessionDTO createdSession = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO createMessageDTO = new AgentSessionMessageCreateDTO();
    createMessageDTO.setContent("Hello kk-studio");

    AgentSessionEventDTO createdEvent =
        agentSessionService.createMessage(createdSession.getSessionId(), createMessageDTO);
    agentRunRuntimeService.scheduleQueuedRun(
        createdEvent.getRunId(), createdSession.getSessionId(), "Hello kk-studio");
    waitForRunCompleted(createdEvent.getRunId());
    assertNotNull(createdEvent);
    assertTrue(createdEvent.getEventId().startsWith("ev_"));
    assertTrue(createdEvent.getRunId().startsWith("rn_"));
    assertEquals(createdSession.getSessionId(), createdEvent.getSessionId());
    assertEquals("root", createdEvent.getParentEventId());
    assertEquals("user_message", createdEvent.getEventType());
    assertEquals("{\"content\":\"Hello kk-studio\"}", createdEvent.getPayloadJson());
    assertNotNull(createdEvent.getCreateTime());

    String currentHeadEventId =
        agentSessionRepository
            .getBySessionId(createdSession.getSessionId())
            .getCurrentHeadEventId();
    assertNotNull(currentHeadEventId);
    assertTrue(currentHeadEventId.startsWith("ev_"));
    assertTrue(!createdEvent.getEventId().equals(currentHeadEventId));

    List<AgentRunDTO> runs = agentRunService.listRuns(createdSession.getSessionId());
    assertEquals(1, runs.size());
    assertEquals(createdEvent.getRunId(), runs.get(0).getRunId());
    assertEquals("succeeded", runs.get(0).getStatus());

    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(createdSession.getSessionId(), null);
    assertEquals(6, events.size());
    assertEquals(createdEvent.getEventId(), events.get(0).getEventId());
    assertEquals(createdEvent.getRunId(), events.get(0).getRunId());
    assertEquals(createdEvent.getPayloadJson(), events.get(0).getPayloadJson());
    assertEquals("set_agent_info", events.get(1).getEventType());
    assertEquals("set_model_info", events.get(2).getEventType());
    assertEquals("assistant_start", events.get(3).getEventType());
    assertEquals("assistant_delta", events.get(4).getEventType());
    assertTrue(events.get(4).getPayloadJson().contains("Agent reply"));
    assertEquals("assistant_end", events.get(5).getEventType());
  }

  /** 同一 session 已存在 queued/running run 时必须拒绝第二条消息，避免产生分叉回答。 */
  @Test
  public void shouldRejectMessageWhileSessionHasActiveRun() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Serialized Message Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO firstMessage = new AgentSessionMessageCreateDTO();
    firstMessage.setContent("first");
    AgentSessionEventDTO firstEvent =
        agentSessionService.createMessage(session.getSessionId(), firstMessage);

    AgentSessionMessageCreateDTO secondMessage = new AgentSessionMessageCreateDTO();
    secondMessage.setContent("second");
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> agentSessionService.createMessage(session.getSessionId(), secondMessage));

    assertEquals("session has active run: " + session.getSessionId(), error.getMessage());
    assertEquals(1, agentRunService.listRuns(session.getSessionId()).size());
    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(session.getSessionId(), null);
    assertEquals(1, events.size());
    assertEquals(firstEvent.getEventId(), events.get(0).getEventId());
    assertEquals(
        firstEvent.getEventId(),
        agentSessionRepository.getBySessionId(session.getSessionId()).getCurrentHeadEventId());
  }

  /** 并发提交必须由 session 行锁串行化，最终只能创建一个 user event 和一个 active run。 */
  @Test
  public void shouldSerializeConcurrentMessageSubmissions() throws Exception {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Concurrent Message Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < 2; index++) {
        String content = "message-" + index;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  AgentSessionMessageCreateDTO message = new AgentSessionMessageCreateDTO();
                  message.setContent(content);
                  try {
                    return agentSessionService.createMessage(session.getSessionId(), message);
                  } catch (RuntimeException error) {
                    return error;
                  }
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();

      List<Object> results = new ArrayList<>();
      for (Future<Object> future : futures) {
        results.add(future.get(5, TimeUnit.SECONDS));
      }

      assertEquals(1, results.stream().filter(AgentSessionEventDTO.class::isInstance).count());
      assertEquals(1, results.stream().filter(IllegalStateException.class::isInstance).count());
      assertEquals(1, agentRunService.listRuns(session.getSessionId()).size());
      assertEquals(1, agentSessionService.listEvents(session.getSessionId(), null).size());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void shouldMarkRunFailedWhenEmbeddedRuntimeFails() throws InterruptedException {
    stubProviderManager.enqueueError("provider timeout");

    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Failure Session");

    AgentSessionDTO createdSession = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO createMessageDTO = new AgentSessionMessageCreateDTO();
    createMessageDTO.setContent("Please fail");

    AgentSessionEventDTO createdEvent =
        agentSessionService.createMessage(createdSession.getSessionId(), createMessageDTO);
    agentRunRuntimeService.scheduleQueuedRun(
        createdEvent.getRunId(), createdSession.getSessionId(), "Please fail");
    waitForRunCompleted(createdEvent.getRunId());

    List<AgentRunDTO> runs = agentRunService.listRuns(createdSession.getSessionId());
    assertEquals(1, runs.size());
    assertEquals(createdEvent.getRunId(), runs.get(0).getRunId());
    assertEquals("failed", runs.get(0).getStatus());

    String currentHeadEventId =
        agentSessionRepository
            .getBySessionId(createdSession.getSessionId())
            .getCurrentHeadEventId();
    assertNotNull(currentHeadEventId);
    assertTrue(!createdEvent.getEventId().equals(currentHeadEventId));

    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(createdSession.getSessionId(), null);
    assertEquals(5, events.size());
    assertEquals("assistant_error", events.get(4).getEventType());
    assertTrue(events.get(4).getPayloadJson().contains("provider timeout"));
  }

  /** 校验 Agent 运行时配置失败通过 failure callback 收敛为 failed，而非 succeeded。 */
  @Test
  public void shouldMarkRunFailedWhenProviderResolutionFails() throws InterruptedException {
    stubProviderManager.failProviderResolution("provider resolution failed");

    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Runtime Configuration Failure Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO createMessageDTO = new AgentSessionMessageCreateDTO();
    createMessageDTO.setContent("Please resolve provider");
    AgentSessionEventDTO createdEvent =
        agentSessionService.createMessage(session.getSessionId(), createMessageDTO);
    agentRunRuntimeService.scheduleQueuedRun(
        createdEvent.getRunId(), session.getSessionId(), createMessageDTO.getContent());
    waitForRunCompleted(createdEvent.getRunId());

    assertEquals("failed", agentRunService.getRun(createdEvent.getRunId()).getStatus());
    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(session.getSessionId(), null);
    assertEquals(1, events.size());
    assertEquals("user_message", events.get(0).getEventType());
  }

  @Test
  public void shouldListEventsAfterSpecifiedEventId() throws InterruptedException {
    stubProviderManager.enqueueText("Agent reply");

    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Incremental Event Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    AgentSessionMessageCreateDTO createMessageDTO = new AgentSessionMessageCreateDTO();
    createMessageDTO.setContent("Hello incremental");
    AgentSessionEventDTO createdEvent =
        agentSessionService.createMessage(session.getSessionId(), createMessageDTO);
    agentRunRuntimeService.scheduleQueuedRun(
        createdEvent.getRunId(), session.getSessionId(), createMessageDTO.getContent());
    waitForRunCompleted(createdEvent.getRunId());

    List<AgentSessionEventDTO> allEvents =
        agentSessionService.listEvents(session.getSessionId(), null);
    // Incremental reads should only return events created after the supplied cursor.
    List<AgentSessionEventDTO> tailEvents =
        agentSessionService.listEventsAfter(session.getSessionId(), allEvents.get(2).getEventId());

    assertEquals(6, allEvents.size());
    assertEquals(3, tailEvents.size());
    assertEquals("assistant_start", tailEvents.get(0).getEventType());
    assertEquals("assistant_delta", tailEvents.get(1).getEventType());
    assertEquals("assistant_end", tailEvents.get(2).getEventType());
    assertTrue(
        agentSessionService
            .listEventsAfter(
                session.getSessionId(), allEvents.get(allEvents.size() - 1).getEventId())
            .isEmpty());
  }

  @Test
  public void shouldRejectInvalidSessionRequests() {
    AgentSessionCreateDTO blankProfileDTO = new AgentSessionCreateDTO();
    blankProfileDTO.setAgentName(" ");

    AgentSessionMessageCreateDTO blankMessageDTO = new AgentSessionMessageCreateDTO();
    blankMessageDTO.setContent(" ");

    assertThrows(IllegalArgumentException.class, () -> agentSessionService.createSession(null));
    assertThrows(
        IllegalArgumentException.class, () -> agentSessionService.createSession(blankProfileDTO));

    AgentSessionCreateDTO missingProfileDTO = new AgentSessionCreateDTO();
    missingProfileDTO.setAgentName("agent-missing");
    assertThrows(
        IllegalArgumentException.class, () -> agentSessionService.createSession(missingProfileDTO));

    assertThrows(IllegalArgumentException.class, () -> agentSessionService.getSession(" "));
    assertThrows(
        IllegalArgumentException.class, () -> agentSessionService.getSession("se_missing"));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.createMessage("se_missing", new AgentSessionMessageCreateDTO()));

    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Invalid Message Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.createMessage(session.getSessionId(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.createMessage(session.getSessionId(), blankMessageDTO));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.updateSession(session.getSessionId(), null));
    assertThrows(IllegalArgumentException.class, () -> agentSessionService.listEvents(" ", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> agentSessionService.listEventsAfter(" ", "ev_placeholder"));
  }

  @Test
  public void shouldHandleExplicitRootHeadAndRejectBrokenBranches() {
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("Branch Validation Session");
    AgentSessionDTO session = agentSessionService.createSession(createDTO);

    assertTrue(agentSessionService.listEvents(session.getSessionId(), "root").isEmpty());
    assertThrows(
        IllegalStateException.class,
        () -> agentSessionService.listEvents(session.getSessionId(), "ev_missing"));

    String eventWithMissingParentId = newEventId("missing_parent");
    addSessionEvent(session.getSessionId(), eventWithMissingParentId, "ev_missing_parent");
    assertThrows(
        IllegalStateException.class,
        () -> agentSessionService.listEvents(session.getSessionId(), eventWithMissingParentId));
    assertThrows(
        IllegalStateException.class,
        () -> agentSessionService.listEvents(session.getSessionId(), "ev_missing_head"));

    AgentSessionDTO cycleSession = agentSessionService.createSession(createDTO);
    String cycleEventAId = newEventId("cycle_a");
    String cycleEventBId = newEventId("cycle_b");
    addSessionEvent(cycleSession.getSessionId(), cycleEventAId, cycleEventBId);
    addSessionEvent(cycleSession.getSessionId(), cycleEventBId, cycleEventAId);
    assertThrows(
        IllegalStateException.class,
        () -> agentSessionService.listEvents(cycleSession.getSessionId(), cycleEventAId));
  }

  private void addSessionEvent(String sessionId, String eventId, String parentEventId) {
    AgentSessionEvent sessionEvent = new AgentSessionEvent();
    sessionEvent.setId(AgentIdGenerator.nextEventId());
    sessionEvent.setEventId(eventId);
    sessionEvent.setSessionId(sessionId);
    sessionEvent.setParentEventId(parentEventId);
    sessionEvent.setRunId("rn_" + eventId);
    sessionEvent.setEventType("test_event");
    sessionEvent.setPayloadJson("{}");
    sessionEvent.setCreateTime(LocalDateTime.now());
    agentSessionEventRepository.add(sessionEvent);
  }

  private String newEventId(String suffix) {
    return "ev_" + suffix + "_" + System.nanoTime();
  }

  private int indexOfSession(List<AgentSessionDTO> sessions, String sessionId) {
    for (int i = 0; i < sessions.size(); i++) {
      if (sessionId.equals(sessions.get(i).getSessionId())) {
        return i;
      }
    }
    return -1;
  }
}
