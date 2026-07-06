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
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.testing.StubProviderManager;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionHeadDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class AgentSessionServiceTest {

  @Autowired private AgentSessionService agentSessionService;

  @Autowired private AgentRunService agentRunService;

  @Autowired private AgentRunRuntimeService agentRunRuntimeService;

  @Autowired private AgentSessionEventRepository agentSessionEventRepository;

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
    assertEquals("active", created.getStatus());
    assertEquals("root", created.getCurrentHeadEventId());
    assertNotNull(created.getCreateTime());
    assertNotNull(created.getUpdateTime());

    AgentSessionDTO loaded = agentSessionService.getSession(created.getSessionId());
    assertEquals(created.getSessionId(), loaded.getSessionId());
    assertEquals(created.getAgentName(), loaded.getAgentName());
    assertEquals(created.getTitle(), loaded.getTitle());
    assertEquals(created.getStatus(), loaded.getStatus());
    assertEquals(created.getCurrentHeadEventId(), loaded.getCurrentHeadEventId());

    List<AgentSessionHeadDTO> heads = agentSessionService.listHeads(created.getSessionId());
    assertEquals(1, heads.size());
    assertTrue(heads.get(0).getHeadId().startsWith("hd_"));
    assertEquals(created.getSessionId(), heads.get(0).getSessionId());
    assertEquals("default", heads.get(0).getHeadName());
    assertEquals("root", heads.get(0).getHeadEventId());

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

  @Test
  public void shouldAppendUserMessageAndAdvanceDefaultHead() throws InterruptedException {
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
    assertEquals("text", createdEvent.getPayloadType());
    assertEquals("{\"content\":\"Hello kk-studio\"}", createdEvent.getPayloadJson());
    assertNotNull(createdEvent.getCreateTime());

    AgentSessionDTO loadedSession = agentSessionService.getSession(createdSession.getSessionId());
    assertNotNull(loadedSession.getCurrentHeadEventId());
    assertTrue(loadedSession.getCurrentHeadEventId().startsWith("ev_"));
    assertTrue(!createdEvent.getEventId().equals(loadedSession.getCurrentHeadEventId()));

    List<AgentSessionHeadDTO> heads = agentSessionService.listHeads(createdSession.getSessionId());
    assertEquals(1, heads.size());
    assertEquals(loadedSession.getCurrentHeadEventId(), heads.get(0).getHeadEventId());

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

    AgentSessionDTO loadedSession = agentSessionService.getSession(createdSession.getSessionId());
    assertNotNull(loadedSession.getCurrentHeadEventId());
    assertTrue(!createdEvent.getEventId().equals(loadedSession.getCurrentHeadEventId()));

    List<AgentSessionEventDTO> events =
        agentSessionService.listEvents(createdSession.getSessionId(), null);
    assertEquals(5, events.size());
    assertEquals("assistant_error", events.get(4).getEventType());
    assertTrue(events.get(4).getPayloadJson().contains("provider timeout"));
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
    assertThrows(IllegalArgumentException.class, () -> agentSessionService.listHeads(" "));
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
    sessionEvent.setPayloadType("text");
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
