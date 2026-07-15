package fun.fengwk.kkstudio.core.harness.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HarnessRunControlCommandServiceTest {

  private static final long SESSION_ID = 11L;
  private static final long RUN_ID = 22L;
  private static final long LEAF_ID = 33L;
  private static final long CONTROL_ID = 44L;
  private static final long NEW_RUN_ID = 55L;
  private static final long NEW_ENTRY_ID = 66L;
  private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");

  private HarnessSessionMapper sessionMapper;
  private HarnessSessionEntryMapper entryMapper;
  private HarnessRunMapper runMapper;
  private RunControlMessageStore controlStore;
  private RunControlIdGenerator controlIds;
  private HarnessRunTransactionService runTransactions;
  private HarnessRunControlCommandService service;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(HarnessSessionMapper.class);
    entryMapper = mock(HarnessSessionEntryMapper.class);
    runMapper = mock(HarnessRunMapper.class);
    controlStore = mock(RunControlMessageStore.class);
    controlIds = mock(RunControlIdGenerator.class);
    runTransactions = mock(HarnessRunTransactionService.class);
    service =
        new HarnessRunControlCommandService(
            sessionMapper, entryMapper, runMapper, controlStore, controlIds, runTransactions);
  }

  /** An active command cannot report acceptance when its durable insert affected no row. */
  @Test
  void rejectsFailedActiveControlInsert() {
    HarnessSessionDO active = session(RUN_ID, LEAF_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(active);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run(RunStatus.RUNNING));
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(active);
    when(entryMapper.findLatestOnPathByType(
            SESSION_ID, LEAF_ID, SessionEntryType.AGENT_SNAPSHOT.value()))
        .thenReturn(snapshot());
    when(controlIds.newControlMessageId()).thenReturn(CONTROL_ID);

    assertThrows(
        IllegalStateException.class,
        () -> service.submit(SESSION_ID, RunControlKind.STEER, user("steer"), NOW));
  }

  /** Accepted controls expose the same millisecond timestamp that their durable row can replay. */
  @Test
  void normalizesAcceptedControlTimestampToDatabasePrecision() {
    Instant precise = Instant.parse("2026-07-16T02:00:00.123456789Z");
    Instant timestamp = precise.truncatedTo(ChronoUnit.MILLIS);
    HarnessSessionDO active = session(RUN_ID, LEAF_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(active);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run(RunStatus.RUNNING));
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(active);
    when(entryMapper.findLatestOnPathByType(
            SESSION_ID, LEAF_ID, SessionEntryType.AGENT_SNAPSHOT.value()))
        .thenReturn(snapshot());
    when(controlIds.newControlMessageId()).thenReturn(CONTROL_ID);
    when(controlStore.insert(any(RunControlMessage.class))).thenReturn(1);

    RunControlMessage result =
        service.submit(SESSION_ID, RunControlKind.STEER, user("steer"), precise);

    assertEquals(timestamp, result.createdAt());
    verify(runTransactions).appendExternalEvents(eq(RUN_ID), anyList(), eq(timestamp));
  }

  /** A direct promotion fails deterministically when the control terminal CAS loses. */
  @Test
  void rejectsFailedDirectPromotionCas() {
    HarnessSessionDO inactive = session(null, LEAF_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(inactive);
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(inactive);
    when(entryMapper.findLatestOnPathByType(
            SESSION_ID, LEAF_ID, SessionEntryType.AGENT_SNAPSHOT.value()))
        .thenReturn(snapshot());
    when(controlIds.newControlMessageId()).thenReturn(CONTROL_ID);
    when(controlStore.insert(any(RunControlMessage.class))).thenReturn(1);
    when(runTransactions.submitUserMessage(SESSION_ID, LEAF_ID, user("follow"), NOW))
        .thenReturn(queuedRun());

    assertThrows(
        IllegalStateException.class,
        () -> service.submit(SESSION_ID, RunControlKind.FOLLOW_UP, user("follow"), NOW));
  }

  /** A FOLLOW_UP observed active but cleared under Run lock is atomically promoted. */
  @Test
  void promotesFollowUpWhenActivePointerClearsUnderLock() {
    HarnessSessionDO hint = session(RUN_ID, LEAF_ID);
    HarnessSessionDO inactive = session(null, LEAF_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(hint);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run(RunStatus.RUNNING));
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(inactive);
    when(entryMapper.findLatestOnPathByType(
            SESSION_ID, LEAF_ID, SessionEntryType.AGENT_SNAPSHOT.value()))
        .thenReturn(snapshot());
    when(controlIds.newControlMessageId()).thenReturn(CONTROL_ID);
    when(controlStore.insert(any(RunControlMessage.class))).thenReturn(1);
    when(runTransactions.submitUserMessage(SESSION_ID, LEAF_ID, user("race"), NOW))
        .thenReturn(queuedRun());
    when(controlStore.markPromoted(CONTROL_ID, NEW_RUN_ID, NEW_ENTRY_ID, NOW)).thenReturn(true);

    RunControlMessage result =
        service.submit(SESSION_ID, RunControlKind.FOLLOW_UP, user("race"), NOW);

    assertEquals(RunControlStatus.PROMOTED, result.status());
    assertEquals(NEW_RUN_ID, result.consumedRunId());
    verify(runTransactions).submitUserMessage(SESSION_ID, LEAF_ID, user("race"), NOW);
  }

  /** Direct promotion requires a current leaf so the USER entry has an explicit parent. */
  @Test
  void rejectsPromotionWithoutSessionLeaf() {
    HarnessSessionDO inactive = session(null, null);
    when(sessionMapper.find(SESSION_ID)).thenReturn(inactive);
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(inactive);

    assertThrows(
        IllegalStateException.class,
        () -> service.submit(SESSION_ID, RunControlKind.FOLLOW_UP, user("follow"), NOW));
  }

  private static HarnessSessionDO session(Long activeRunId, Long leafEntryId) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(SESSION_ID);
    session.setActiveRunId(activeRunId);
    session.setLeafEntryId(leafEntryId);
    return session;
  }

  private static HarnessRunDO run(RunStatus status) {
    HarnessRunDO run = new HarnessRunDO();
    run.setId(RUN_ID);
    run.setSessionId(SESSION_ID);
    run.setStatus(status.name());
    return run;
  }

  private static HarnessSessionEntryDO snapshot() {
    AgentSnapshotEntryPayload payload =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(LEAF_ID);
    entry.setSessionId(SESSION_ID);
    entry.setEntryType(SessionEntryType.AGENT_SNAPSHOT.value());
    entry.setPayloadJson(new SessionEntryJsonCodec().encode(payload));
    return entry;
  }

  private static AgentRun queuedRun() {
    return new AgentRun(
        NEW_RUN_ID,
        SESSION_ID,
        NEW_ENTRY_ID,
        RunStatus.QUEUED,
        0,
        0,
        0,
        null,
        null,
        NOW,
        null,
        NOW,
        null,
        null,
        NOW);
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }
}
