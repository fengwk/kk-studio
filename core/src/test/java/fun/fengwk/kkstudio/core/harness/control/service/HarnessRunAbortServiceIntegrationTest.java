package fun.fengwk.kkstudio.core.harness.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.control.store.MysqlRunControlMessageStore;
import fun.fengwk.kkstudio.core.harness.control.store.SnowflakeRunControlIdGenerator;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.run.DeltaFlushScheduler;
import fun.fengwk.kkstudio.harness.runtime.run.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.run.RunWorkerConfig;
import fun.fengwk.kkstudio.harness.runtime.run.ToolPreparationPort;
import fun.fengwk.kkstudio.harness.runtime.run.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-level abort contracts for durable controls, terminal repair and transaction rollback.
 */
@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRunAbortServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-16T01:00:00Z");

  @Autowired private HarnessRunAbortService abortService;
  @Autowired private HarnessRunControlCommandService commandService;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private MysqlRunControlMessageStore controlStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private SnowflakeRunIdGenerator runIds;
  @Autowired private SnowflakeRunControlIdGenerator controlIds;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute("alter table harness_run_event drop constraint if exists unique_event_type");
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_subagent_task");
    jdbc.update("delete from tool_invocation");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  /** A session without an active Run still clears run-less pending controls exactly once. */
  @Test
  void noActiveRunClearsRunlessControlsWithoutCreatingEvents() {
    Seed seed = seedSession();
    RunControlMessage control = insertPending(seed.sessionId(), null, RunControlKind.FOLLOW_UP);

    HarnessRunAbortService.AbortResult first = abortService.abort(seed.sessionId(), NOW);
    HarnessRunAbortService.AbortResult repeated =
        abortService.abort(seed.sessionId(), NOW.plusSeconds(1));

    assertNull(first.runId());
    assertFalse(first.newlyRequested());
    assertFalse(repeated.newlyRequested());
    RunControlMessage stored = controlStore.find(control.id()).orElseThrow();
    assertEquals(RunControlStatus.CLEARED, stored.status());
    assertEquals(NOW, stored.consumedAt());
    assertEquals(0L, jdbc.queryForObject("select count(*) from harness_run_event", Long.class));
  }

  /** The first active abort is journaled once and atomically clears every pending control. */
  @Test
  void activeAbortIsDurableIdempotentAndClearsAllControls() {
    Claimed claimed = claimedRun();
    RunControlMessage steer =
        commandService.submit(
            claimed.sessionId(), RunControlKind.STEER, user("redirect"), NOW.plusSeconds(2));
    RunControlMessage followUp =
        commandService.submit(
            claimed.sessionId(), RunControlKind.FOLLOW_UP, user("later"), NOW.plusSeconds(3));

    HarnessRunAbortService.AbortResult first =
        abortService.abort(claimed.sessionId(), NOW.plusSeconds(4));
    HarnessRunAbortService.AbortResult repeated =
        abortService.abort(claimed.sessionId(), NOW.plusSeconds(5));

    assertTrue(first.newlyRequested());
    assertFalse(repeated.newlyRequested());
    assertEquals(first.requestedAt(), repeated.requestedAt());
    AgentRun storedRun = runStore.find(claimed.run().id()).orElseThrow();
    assertEquals(first.requestedAt(), storedRun.cancelRequestedAt());
    assertFalse(
        runStore.heartbeat(
            claimed.run().id(),
            claimed.run().leaseOwner(),
            claimed.run().attempt(),
            NOW.plusSeconds(6),
            Duration.ofSeconds(30)));
    assertEquals(RunControlStatus.CLEARED, controlStore.find(steer.id()).orElseThrow().status());
    assertEquals(RunControlStatus.CLEARED, controlStore.find(followUp.id()).orElseThrow().status());
    assertEquals(1L, eventCount(claimed.run().id(), RunEventType.ABORT_REQUESTED));
    String payload =
        jdbc.queryForObject(
            "select payload_json from harness_run_event where run_id=? and event_type=?",
            String.class,
            claimed.run().id(),
            RunEventType.ABORT_REQUESTED.value());
    assertNotNull(payload);
    assertTrue(payload.contains("\"sessionId\":" + claimed.sessionId()));
    assertTrue(payload.contains("\"runId\":" + claimed.run().id()));
    assertTrue(payload.contains("\"status\":\"RUNNING\""));
  }

  /** A reconstructed worker consumes a queued cancel before context or Provider setup. */
  @Test
  void queuedAbortSurvivesWorkerReconstructionAndCancelsBeforeProvider() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("queued"), NOW);
    abortService.abort(seed.sessionId(), NOW.plusSeconds(1));
    SessionContextBuilder contextBuilder = mock(SessionContextBuilder.class);
    TurnResourceResolver resourceResolver = mock(TurnResourceResolver.class);
    AgentTurnWorker reconstructed =
        new AgentTurnWorker(
            runStore,
            runStore,
            transactions,
            mock(ToolPreparationPort.class),
            contextBuilder,
            new ProviderMessageProjector(),
            resourceResolver,
            mock(CompactionService.class),
            RunWorkerConfig.DEFAULT,
            Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC),
            mock(DeltaFlushScheduler.class));

    assertTrue(reconstructed.executeNext("restarted-worker").isPresent());

    assertEquals(RunStatus.CANCELLED, runStore.find(queued.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
    assertEquals(1L, eventCount(queued.id(), RunEventType.ABORT_REQUESTED));
    assertEquals(1L, eventCount(queued.id(), RunEventType.RUN_CANCELLED));
    verifyNoInteractions(contextBuilder, resourceResolver);
  }

  /** A stale terminal active pointer is repaired without fabricating an abort request. */
  @Test
  void terminalRunRepairsActivePointerWithoutAbortEvent() {
    Claimed claimed = claimedRun();
    insertPending(claimed.sessionId(), claimed.run().id(), RunControlKind.STEER);
    jdbc.update(
        "update harness_run set status='SUCCEEDED', lease_owner=null, lease_until=null,"
            + " finished_at=?, gmt_modified=? where id=?",
        Timestamp.from(NOW.plusSeconds(2)),
        Timestamp.from(NOW.plusSeconds(2)),
        claimed.run().id());

    HarnessRunAbortService.AbortResult result =
        abortService.abort(claimed.sessionId(), NOW.plusSeconds(3));

    assertFalse(result.newlyRequested());
    assertEquals(RunStatus.SUCCEEDED, result.status());
    assertNull(sessionStore.find(claimed.sessionId()).orElseThrow().activeRunId());
    assertEquals(0L, eventCount(claimed.run().id(), RunEventType.ABORT_REQUESTED));
    assertEquals(0L, pendingControlCount(claimed.sessionId()));
  }

  /** A late event constraint failure rolls back Run cancellation and control clearing together. */
  @Test
  void lateAbortEventFailureRollsBackAllState() {
    Claimed claimed = claimedRun();
    RunControlMessage pending =
        insertPending(claimed.sessionId(), claimed.run().id(), RunControlKind.STEER);
    jdbc.execute(
        "alter table harness_run_event add constraint unique_event_type unique (event_type)");
    jdbc.update(
        "insert into harness_run_event (id, run_id, sequence, event_type, payload_json, gmt_create)"
            + " values (?, ?, 1, ?, '{\"schemaVersion\":1}', ?)",
        runIds.newRunEventId(),
        claimed.run().id(),
        RunEventType.ABORT_REQUESTED.value(),
        Timestamp.from(NOW));
    jdbc.update("update harness_run set event_sequence=1 where id=?", claimed.run().id());

    assertThrows(
        RuntimeException.class, () -> abortService.abort(claimed.sessionId(), NOW.plusSeconds(2)));

    assertNull(runStore.find(claimed.run().id()).orElseThrow().cancelRequestedAt());
    assertEquals(RunControlStatus.PENDING, controlStore.find(pending.id()).orElseThrow().status());
    assertEquals(1L, eventCount(claimed.run().id(), RunEventType.ABORT_REQUESTED));
  }

  private Claimed claimedRun() {
    Seed seed = seedSession();
    transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("initial"), NOW);
    AgentRun run =
        runStore.claimDue("abort-worker", NOW.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
    return new Claimed(seed.sessionId(), run);
  }

  private Seed seedSession() {
    long sessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(sessionId, 1L, "abort-test", false, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload payload =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot(
                "system", "model-default", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, payload.type(), payload, NOW),
        null,
        0L);
    return new Seed(sessionId, snapshotId);
  }

  private RunControlMessage insertPending(long sessionId, Long runId, RunControlKind kind) {
    RunControlMessage control =
        new RunControlMessage(
            controlIds.newControlMessageId(),
            sessionId,
            runId,
            kind,
            ControlConsumptionMode.ONE_AT_A_TIME,
            user("pending"),
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    assertEquals(1, controlStore.insert(control));
    return control;
  }

  private long pendingControlCount(long sessionId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_run_control_message where session_id=? and"
                + " status='PENDING'",
            Long.class,
            sessionId);
    return count == null ? 0L : count;
  }

  private long eventCount(long runId, RunEventType type) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_run_event where run_id=? and event_type=?",
            Long.class,
            runId,
            type.value());
    return count == null ? 0L : count;
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private record Seed(long sessionId, long snapshotId) {}

  private record Claimed(long sessionId, AgentRun run) {}
}
