package fun.fengwk.kkstudio.core.harness.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class HarnessRunAbortServiceTest {

  private static final long SESSION_ID = 11L;
  private static final long RUN_ID = 22L;
  private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

  private HarnessSessionMapper sessionMapper;
  private HarnessRunMapper runMapper;
  private ToolInvocationMapper invocationMapper;
  private HarnessSubagentTaskMapper taskMapper;
  private RunControlMessageStore controlStore;
  private HarnessRunEventWriter eventWriter;
  private TaskRuntime taskRuntime;
  private HarnessRunAbortService service;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(HarnessSessionMapper.class);
    runMapper = mock(HarnessRunMapper.class);
    invocationMapper = mock(ToolInvocationMapper.class);
    taskMapper = mock(HarnessSubagentTaskMapper.class);
    controlStore = mock(RunControlMessageStore.class);
    eventWriter = mock(HarnessRunEventWriter.class);
    taskRuntime = mock(TaskRuntime.class);
    service =
        new HarnessRunAbortService(
            sessionMapper,
            runMapper,
            invocationMapper,
            taskMapper,
            controlStore,
            eventWriter,
            taskRuntime);
  }

  /** Unknown sessions fail before any durable side effect can occur. */
  @Test
  void rejectsUnknownSessionWithoutSideEffects() {
    assertThrows(IllegalArgumentException.class, () -> service.abort(SESSION_ID, NOW));

    verifyNoInteractions(runMapper, invocationMapper, taskMapper, controlStore, eventWriter);
  }

  /** The no-active path locks Session/Root and clears controls including run-less follow-ups. */
  @Test
  void clearsPendingControlsWhenNoRunIsActive() {
    HarnessSessionDO session = session(null);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(session);

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, NOW);

    assertEquals(SESSION_ID, result.sessionId());
    assertNull(result.runId());
    assertNull(result.status());
    assertNull(result.requestedAt());
    verify(eventWriter).lockRoot(session);
    verify(controlStore).clearPendingBySession(SESSION_ID, NOW);
    verifyNoInteractions(runMapper, invocationMapper, taskMapper, taskRuntime);
  }

  /** A run appearing after the no-active hint is a conflict instead of a Session-to-Run relock. */
  @Test
  void rejectsRunAppearingAfterNoActiveHint() {
    when(sessionMapper.find(SESSION_ID)).thenReturn(session(null));
    when(sessionMapper.findForUpdate(SESSION_ID)).thenReturn(session(RUN_ID));

    assertThrows(RunAbortConflictException.class, () -> service.abort(SESSION_ID, NOW));

    verify(controlStore, never()).clearPendingBySession(SESSION_ID, NOW);
    verifyNoInteractions(runMapper, invocationMapper, taskMapper, taskRuntime);
  }

  /** A missing hinted Run is repaired only while Session still points at the same id. */
  @Test
  void repairsMissingRunPointerAndKeepsItsIdInTheResult() {
    HarnessSessionDO session = session(RUN_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);
    when(sessionMapper.clearActiveRun(SESSION_ID, RUN_ID, utc(NOW))).thenReturn(1);

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, NOW);

    assertEquals(RUN_ID, result.runId());
    assertNull(result.status());
    verify(sessionMapper).clearActiveRun(SESSION_ID, RUN_ID, utc(NOW));
    verify(controlStore).clearPendingBySession(SESSION_ID, NOW);
    verifyNoInteractions(invocationMapper, taskMapper, taskRuntime);
  }

  /** A terminal Run cannot receive a synthetic abort event; only its stale pointer is repaired. */
  @Test
  void repairsTerminalRunPointerWithoutRequestingCancellation() {
    HarnessSessionDO session = session(RUN_ID);
    HarnessRunDO run = run(RunStatus.SUCCEEDED, null);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run);
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);
    when(sessionMapper.clearActiveRun(SESSION_ID, RUN_ID, utc(NOW))).thenReturn(1);

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, NOW);

    assertEquals(RunStatus.SUCCEEDED, result.status());
    assertFalse(result.newlyRequested());
    verify(runMapper, never()).requestCancel(RUN_ID, utc(NOW));
    verifyNoInteractions(invocationMapper, taskMapper, taskRuntime);
  }

  /** The first abort writes one event, clears controls and propagates to tools and task trees. */
  @SuppressWarnings("unchecked")
  @Test
  void persistsAndPropagatesFirstCancellationRequest() {
    HarnessSessionDO session = session(RUN_ID);
    HarnessRunDO run = run(RunStatus.RUNNING, null);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run);
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);
    when(runMapper.requestCancel(RUN_ID, utc(NOW))).thenReturn(1);
    when(taskMapper.listTaskInvocationIdsByParentRun(RUN_ID)).thenReturn(List.of(31L, 32L));

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, NOW);

    assertTrue(result.newlyRequested());
    assertEquals(RunStatus.RUNNING, result.status());
    assertEquals(NOW, result.requestedAt());
    ArgumentCaptor<List<RunEventDraft>> events = ArgumentCaptor.forClass(List.class);
    verify(eventWriter).appendLocked(eq(run), events.capture(), eq(NOW));
    assertEquals(1, events.getValue().size());
    assertEquals(RunEventType.ABORT_REQUESTED, events.getValue().get(0).type());
    assertTrue(events.getValue().get(0).payloadJson().contains("\"sessionId\":11"));
    assertTrue(events.getValue().get(0).payloadJson().contains("\"runId\":22"));
    assertTrue(events.getValue().get(0).payloadJson().contains("\"status\":\"RUNNING\""));
    verify(controlStore).clearPendingBySession(SESSION_ID, NOW);
    InOrder cancellationOrder = inOrder(taskRuntime, invocationMapper);
    cancellationOrder.verify(taskRuntime).cancelTree(31L, NOW);
    cancellationOrder.verify(taskRuntime).cancelTree(32L, NOW);
    cancellationOrder.verify(invocationMapper).requestCancelByRun(RUN_ID, utc(NOW));
  }

  /** First responses use the same millisecond precision that the durable timestamp can replay. */
  @Test
  void normalizesNewCancellationTimestampToDatabasePrecision() {
    Instant precise = Instant.parse("2026-07-16T00:00:00.123456789Z");
    Instant timestamp = precise.truncatedTo(ChronoUnit.MILLIS);
    HarnessSessionDO session = session(RUN_ID);
    HarnessRunDO run = run(RunStatus.RUNNING, null);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run);
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);
    when(runMapper.requestCancel(RUN_ID, utc(timestamp))).thenReturn(1);
    when(taskMapper.listTaskInvocationIdsByParentRun(RUN_ID)).thenReturn(List.of());

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, precise);

    assertEquals(timestamp, result.requestedAt());
    verify(eventWriter).appendLocked(eq(run), anyList(), eq(timestamp));
    verify(controlStore).clearPendingBySession(SESSION_ID, timestamp);
    verify(invocationMapper).requestCancelByRun(RUN_ID, utc(timestamp));
  }

  /** Repeated aborts retain the first timestamp and do not duplicate the requested event. */
  @Test
  void repeatsPropagationWithoutDuplicatingAbortEvent() {
    Instant requestedAt = NOW.minusSeconds(3);
    HarnessSessionDO session = session(RUN_ID);
    HarnessRunDO run = run(RunStatus.WAITING_TOOLS, requestedAt);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run);
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);
    when(taskMapper.listTaskInvocationIdsByParentRun(RUN_ID)).thenReturn(List.of());

    HarnessRunAbortService.AbortResult result = service.abort(SESSION_ID, NOW);

    assertFalse(result.newlyRequested());
    assertEquals(requestedAt, result.requestedAt());
    verify(eventWriter, never()).appendLocked(eq(run), anyList(), eq(NOW));
    verify(controlStore).clearPendingBySession(SESSION_ID, NOW);
    verify(invocationMapper).requestCancelByRun(RUN_ID, utc(NOW));
  }

  /** A zero-row cancel CAS without a prior timestamp is an invariant violation, not idempotency. */
  @Test
  void rejectsUnexpectedCancelCasFailure() {
    HarnessSessionDO session = session(RUN_ID);
    when(sessionMapper.find(SESSION_ID)).thenReturn(session);
    when(runMapper.findForUpdate(RUN_ID)).thenReturn(run(RunStatus.QUEUED, null));
    when(eventWriter.lockSessionAndRoot(SESSION_ID)).thenReturn(session);

    assertThrows(IllegalStateException.class, () -> service.abort(SESSION_ID, NOW));

    verifyNoInteractions(invocationMapper, taskMapper, controlStore, taskRuntime);
  }

  private static HarnessSessionDO session(Long activeRunId) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(SESSION_ID);
    session.setActiveRunId(activeRunId);
    return session;
  }

  private static HarnessRunDO run(RunStatus status, Instant requestedAt) {
    HarnessRunDO run = new HarnessRunDO();
    run.setId(RUN_ID);
    run.setSessionId(SESSION_ID);
    run.setStatus(status.name());
    run.setCancelRequestedAt(requestedAt == null ? null : utc(requestedAt));
    return run;
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
