package fun.fengwk.kkstudio.core.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.control.store.MysqlRunControlMessageStore;
import fun.fengwk.kkstudio.core.harness.control.store.SnowflakeRunControlIdGenerator;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRunControlConsumptionIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlRunControlMessageStore controlStore;
  @Autowired private SnowflakeRunControlIdGenerator controlIdGen;
  @Autowired private SnowflakeRunIdGenerator idGenerator;
  @Autowired private SnowflakeSessionIdGenerator sessionIdGen;
  @Autowired private DatabaseToolPreparationPort toolPreparation;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  private Seed seedSession() {
    long sessionId = sessionIdGen.newSessionId();
    Session session = Session.root(sessionId, 1L, "control-test", false, NOW);
    sessionStore.create(session);
    long snapshotId = idGenerator.newSessionEntryId();
    AgentSnapshot snapshot =
        new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}");
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, payload.type(), payload, NOW),
        null,
        0L);
    return new Seed(sessionId, snapshotId);
  }

  private AgentRun submitAndClaim(Seed seed) {
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    return runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();
  }

  private long insertControl(
      long sessionId, Long runId, RunControlKind kind, ControlConsumptionMode mode) {
    RunControlMessage msg =
        new RunControlMessage(
            controlIdGen.newControlMessageId(),
            sessionId,
            runId,
            kind,
            mode,
            user("control-" + kind),
            RunControlStatus.PENDING,
            null,
            null,
            NOW,
            null);
    controlStore.insert(msg);
    return msg.id();
  }

  private int totalEntryCount(long sessionId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_session_entry where session_id = ?",
            Long.class,
            sessionId);
    return count != null ? count.intValue() : 0;
  }

  @Test
  void consumesSteerOneAtATimeExactlyOnce() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME);
    long c2 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME);

    assertTrue(transactions.consumeSteering(claimed, NOW.plusSeconds(1)));

    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());
    assertNotNull(controlStore.find(c1).orElseThrow().consumedEntryId());
    assertEquals(RunControlStatus.PENDING, controlStore.find(c2).orElseThrow().status());
    assertEquals(3, totalEntryCount(seed.sessionId()));
    assertTrue(
        runStore.listAfter(claimed.id(), 0, 100).stream()
            .anyMatch(e -> e.type() == RunEventType.STEER_CONSUMED));
  }

  @Test
  void consumesSteerAllModeBatch() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(), claimed.id(), RunControlKind.STEER, ControlConsumptionMode.ALL);
    long c2 =
        insertControl(
            seed.sessionId(), claimed.id(), RunControlKind.STEER, ControlConsumptionMode.ALL);

    assertTrue(transactions.consumeSteering(claimed, NOW.plusSeconds(1)));

    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());
    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c2).orElseThrow().status());
    assertEquals(4, totalEntryCount(seed.sessionId()));
  }

  @Test
  void consumeSteeringDuplicateCallDoesNotDuplicateEntries() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME);

    assertTrue(transactions.consumeSteering(claimed, NOW.plusSeconds(1)));
    assertTrue(transactions.consumeSteering(claimed, NOW.plusSeconds(2)));

    assertEquals(3, totalEntryCount(seed.sessionId()));
    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());
  }

  @Test
  void consumeSteeringReturnsFalseWhenOwnershipLost() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    runStore.claimDue("worker-2", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
    assertFalse(transactions.consumeSteering(claimed, NOW.plusSeconds(31)));
  }

  // ---- no-tool completion: STEER priority ----

  @Test
  void completeRequeuesWhenSteerPending() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    insertControl(
        seed.sessionId(), claimed.id(), RunControlKind.STEER, ControlConsumptionMode.ONE_AT_A_TIME);

    assertTrue(
        transactions.complete(
            claimed, assistant("ok"), assistantCompleted(claimed), NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(claimed.id()).orElseThrow();
    assertEquals(RunStatus.QUEUED, stored.status());
    assertEquals(1, stored.turnIndex());
    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    assertEquals(RunEventType.ASSISTANT_COMPLETED, events.get(0).type());
    assertEquals(RunEventType.RUN_REQUEUED, events.get(1).type());
    assertEquals(1, controlStore.listPendingByRun(claimed.id(), RunControlKind.STEER).size());
  }

  // ---- FOLLOW_UP ONE/ALL ----

  @Test
  void completeConsumesFollowUpOneAtATime() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    insertControl(
        seed.sessionId(),
        claimed.id(),
        RunControlKind.FOLLOW_UP,
        ControlConsumptionMode.ONE_AT_A_TIME);
    long c2 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.FOLLOW_UP,
            ControlConsumptionMode.ONE_AT_A_TIME);

    assertTrue(
        transactions.complete(
            claimed, assistant("ok"), assistantCompleted(claimed), NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(claimed.id()).orElseThrow();
    assertEquals(RunStatus.QUEUED, stored.status());
    assertEquals(4, totalEntryCount(seed.sessionId()));
    assertEquals(RunControlStatus.PENDING, controlStore.find(c2).orElseThrow().status());
    assertTrue(
        runStore.listAfter(claimed.id(), 0, 100).stream()
            .anyMatch(e -> e.type() == RunEventType.FOLLOW_UP_CONSUMED));
  }

  @Test
  void completeConsumesAllFollowUpInAllMode() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(), claimed.id(), RunControlKind.FOLLOW_UP, ControlConsumptionMode.ALL);
    long c2 =
        insertControl(
            seed.sessionId(), claimed.id(), RunControlKind.FOLLOW_UP, ControlConsumptionMode.ALL);

    assertTrue(
        transactions.complete(
            claimed, assistant("ok"), assistantCompleted(claimed), NOW.plusSeconds(1)));

    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());
    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c2).orElseThrow().status());
    assertEquals(5, totalEntryCount(seed.sessionId()));
  }

  // ---- FAILED promotion ----

  @Test
  void failedPromotesPendingControlsToNewRun() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME);
    long c2 =
        insertControl(
            seed.sessionId(), claimed.id(), RunControlKind.FOLLOW_UP, ControlConsumptionMode.ALL);

    List<RunEventDraft> terminalEvents =
        List.of(
            new RunEventDraft(
                RunEventType.ASSISTANT_FAILED,
                RunEventPayloads.forAttempt(claimed, "reason", "test")),
            new RunEventDraft(
                RunEventType.RUN_FAILED, RunEventPayloads.forAttempt(claimed, "reason", "test")));
    assertTrue(
        transactions.terminate(claimed, RunStatus.FAILED, terminalEvents, NOW.plusSeconds(1)));

    AgentRun oldRun = runStore.find(claimed.id()).orElseThrow();
    assertEquals(RunStatus.FAILED, oldRun.status());
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertNotNull(session.activeRunId());
    assertTrue(session.activeRunId() != claimed.id());
    AgentRun newRun = runStore.find(session.activeRunId()).orElseThrow();
    assertEquals(RunStatus.QUEUED, newRun.status());
    // triggerEntryId must equal first promoted entry
    assertEquals(controlStore.find(c1).orElseThrow().consumedEntryId(), newRun.triggerEntryId());

    assertEquals(RunControlStatus.PROMOTED, controlStore.find(c1).orElseThrow().status());
    assertEquals(RunControlStatus.PROMOTED, controlStore.find(c2).orElseThrow().status());

    // consumedEntryId ascending matches entry order
    long eid1 = controlStore.find(c1).orElseThrow().consumedEntryId();
    long eid2 = controlStore.find(c2).orElseThrow().consumedEntryId();
    assertTrue(eid1 < eid2);
    assertEquals(
        claimed.triggerEntryId(),
        jdbc.queryForObject(
            "select parent_entry_id from harness_session_entry where id = ?", Long.class, eid1));
    assertEquals(
        eid1,
        jdbc.queryForObject(
            "select parent_entry_id from harness_session_entry where id = ?", Long.class, eid2));
    assertEquals(
        newRun.id(),
        jdbc.queryForObject(
            "select run_id from harness_session_entry where id = ?", Long.class, eid1));
    assertEquals(
        newRun.id(),
        jdbc.queryForObject(
            "select run_id from harness_session_entry where id = ?", Long.class, eid2));
    assertEquals(eid2, session.leafEntryId());

    // Events: terminal events then CONTROL_PROMOTED events
    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    assertEquals(4, events.size());
    assertEquals(RunEventType.ASSISTANT_FAILED, events.get(0).type());
    assertEquals(RunEventType.RUN_FAILED, events.get(1).type());
    assertEquals(RunEventType.CONTROL_PROMOTED, events.get(2).type());
    assertEquals(RunEventType.CONTROL_PROMOTED, events.get(3).type());
    assertTrue(events.get(2).payloadJson().contains("\"targetRunId\":" + newRun.id()));
    assertTrue(events.get(2).payloadJson().contains("\"entryId\":" + eid1));
    assertTrue(events.get(3).payloadJson().contains("\"targetRunId\":" + newRun.id()));
    assertTrue(events.get(3).payloadJson().contains("\"entryId\":" + eid2));

    // Two runs: old (FAILED) + new (QUEUED)
    assertEquals(
        2,
        jdbc.queryForObject(
            "select count(*) from harness_run where session_id = ?",
            Integer.class,
            seed.sessionId()));
  }

  @Test
  void failedWithoutPendingClearsActiveRun() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);

    assertTrue(
        transactions.terminate(
            claimed,
            RunStatus.FAILED,
            List.of(
                new RunEventDraft(
                    RunEventType.ASSISTANT_FAILED,
                    RunEventPayloads.forAttempt(claimed, "reason", "test")),
                new RunEventDraft(
                    RunEventType.RUN_FAILED,
                    RunEventPayloads.forAttempt(claimed, "reason", "test"))),
            NOW.plusSeconds(1)));

    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertNull(session.activeRunId());
    assertEquals(RunStatus.FAILED, runStore.find(claimed.id()).orElseThrow().status());
  }

  // ---- cancel-wins ----

  @Test
  void cancelWinsInComplete() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        transactions.complete(
            claimedWithCancel,
            assistant("ok"),
            assistantCompleted(claimedWithCancel),
            NOW.plusSeconds(32)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    assertEquals(2, totalEntryCount(seed.sessionId()));
    assertTrue(
        runStore.listAfter(claimed.id(), 0, 100).stream()
            .anyMatch(e -> e.type() == RunEventType.RUN_CANCELLED));
  }

  @Test
  void cancelWinsInRequeue() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        transactions.requeue(
            claimedWithCancel,
            NOW.plusSeconds(60),
            List.of(
                new RunEventDraft(
                    RunEventType.ASSISTANT_FAILED,
                    RunEventPayloads.forAttempt(claimedWithCancel, "reason", "transient"))),
            NOW.plusSeconds(32)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
    assertTrue(
        runStore.listAfter(claimed.id(), 0, 100).stream()
            .anyMatch(e -> e.type() == RunEventType.RUN_CANCELLED));
  }

  @Test
  void cancelWinsInCompactAndRequeue() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        transactions.compactAndRequeue(
            claimedWithCancel,
            new CompactionEntryPayload("summary", seed.snapshotId(), 100, "{}"),
            NOW.plusSeconds(60),
            List.of(
                new RunEventDraft(
                    RunEventType.ASSISTANT_FAILED,
                    RunEventPayloads.forAttempt(claimedWithCancel, "reason", "overflow"))),
            NOW.plusSeconds(32)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    // No compaction entry appended
    assertEquals(2, totalEntryCount(seed.sessionId()));
  }

  @Test
  void cancelWinsInTerminateFailed() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    // Insert a control that should be cleared
    insertControl(
        seed.sessionId(), claimed.id(), RunControlKind.STEER, ControlConsumptionMode.ONE_AT_A_TIME);
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        transactions.terminate(
            claimedWithCancel,
            RunStatus.FAILED,
            List.of(
                new RunEventDraft(
                    RunEventType.ASSISTANT_FAILED,
                    RunEventPayloads.forAttempt(claimedWithCancel, "reason", "fail")),
                new RunEventDraft(
                    RunEventType.RUN_FAILED,
                    RunEventPayloads.forAttempt(claimedWithCancel, "reason", "fail"))),
            NOW.plusSeconds(32)));

    // CANCELLED, not FAILED; RUN_FAILED filtered out
    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
    // Control cleared
    assertTrue(controlStore.listPendingBySession(seed.sessionId()).isEmpty());
    // Only one RUN_CANCELLED, no RUN_FAILED
    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    long cancelledCount =
        events.stream().filter(e -> e.type() == RunEventType.RUN_CANCELLED).count();
    assertEquals(1, cancelledCount);
    // ASSISTANT_FAILED preserved
    assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.ASSISTANT_FAILED));
  }

  // ---- event failure rolls back ----

  @Test
  void completeRollsBackWhenEventCannotBePersisted() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.complete(
                claimed,
                assistant("ok"),
                new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, "{}"),
                NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(claimed.id()).orElseThrow();
    assertEquals(RunStatus.RUNNING, stored.status());
    assertEquals(2, totalEntryCount(seed.sessionId()));
  }

  // ---- lease reclaim does not replay ----

  @Test
  void leaseReclaimDoesNotReplayConsumedSteer() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    long c1 =
        insertControl(
            seed.sessionId(),
            claimed.id(),
            RunControlKind.STEER,
            ControlConsumptionMode.ONE_AT_A_TIME);
    assertTrue(transactions.consumeSteering(claimed, NOW.plusSeconds(1)));
    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());

    AgentRun reclaim =
        runStore.claimDue("worker-2", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
    assertTrue(transactions.consumeSteering(reclaim, NOW.plusSeconds(31)));

    assertEquals(3, totalEntryCount(seed.sessionId()));
    assertEquals(RunControlStatus.CONSUMED, controlStore.find(c1).orElseThrow().status());
  }

  // ---- clearPendingBySession ----

  @Test
  void clearPendingBySessionClearsNullRunIdAndCurrentRunRows() {
    Seed seed = seedSession();
    // Insert control with null run_id (FOLLOW_UP before active run)
    long c1 =
        insertControl(
            seed.sessionId(), null, RunControlKind.FOLLOW_UP, ControlConsumptionMode.ONE_AT_A_TIME);
    // Insert control assigned to a different session
    long otherSession = sessionIdGen.newSessionId();
    long c2 =
        insertControl(
            otherSession, null, RunControlKind.FOLLOW_UP, ControlConsumptionMode.ONE_AT_A_TIME);

    controlStore.clearPendingBySession(seed.sessionId(), NOW.plusSeconds(1));

    assertEquals(RunControlStatus.CLEARED, controlStore.find(c1).orElseThrow().status());
    assertEquals(RunControlStatus.PENDING, controlStore.find(c2).orElseThrow().status());
  }

  // ---- consumeSteering cancel-wins ----

  @Test
  void consumeSteeringCancelWins() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    insertControl(
        seed.sessionId(), claimed.id(), RunControlKind.STEER, ControlConsumptionMode.ONE_AT_A_TIME);
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertFalse(transactions.consumeSteering(claimedWithCancel, NOW.plusSeconds(32)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
    assertTrue(controlStore.listPendingBySession(seed.sessionId()).isEmpty());
  }

  // ---- prepareTools cancel-wins ----

  @Test
  void cancelWinsInPrepareTools() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    ToolCall call = new ToolCall("call-1", "read", "{}");
    assertTrue(runStore.requestCancel(claimed.id(), NOW.plusSeconds(1)));
    AgentRun claimedWithCancel =
        runStore.claimDue("worker", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        toolPreparation.prepare(
            claimedWithCancel,
            assistant("calling", List.of(call)),
            List.of(call),
            List.of(toolBinding()),
            Path.of("."),
            Path.of("."),
            List.of(assistantCompleted(claimedWithCancel)),
            NOW.plusSeconds(32)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    // No assistant entry, no tool invocation
    assertEquals(2, totalEntryCount(seed.sessionId()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from tool_invocation where run_id = ?", Integer.class, claimed.id()));
  }

  // ---- terminate(CANCELLED) preserves caller events ----

  @Test
  void terminateCancelledPreservesCallerEventsAndProducesSingleRunCancelled() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    assertTrue(
        transactions.terminate(
            claimed,
            RunStatus.CANCELLED,
            List.of(
                new RunEventDraft(
                    RunEventType.ASSISTANT_FAILED,
                    RunEventPayloads.forAttempt(claimed, "reason", "tool")),
                new RunEventDraft(
                    RunEventType.RUN_CANCELLED,
                    RunEventPayloads.forAttempt(claimed, "reason", "cancelled"))),
            NOW.plusSeconds(1)));

    assertEquals(RunStatus.CANCELLED, runStore.find(claimed.id()).orElseThrow().status());
    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    // Exactly one RUN_CANCELLED, no replacement (caller-provided preserved)
    long cancelledCount =
        events.stream().filter(e -> e.type() == RunEventType.RUN_CANCELLED).count();
    assertEquals(1, cancelledCount);
    assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.ASSISTANT_FAILED));
  }

  // ---- complete validates single ASSISTANT_COMPLETED ----

  @Test
  void completeRejectsWrongEventType() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.complete(
                claimed,
                assistant("ok"),
                new RunEventDraft(RunEventType.RUN_FAILED, "{}"),
                NOW.plusSeconds(1)));
  }

  // ---- complete returns false when lockOwned fails ----

  @Test
  void completeReturnsFalseWhenOwnershipLost() {
    Seed seed = seedSession();
    AgentRun claimed = submitAndClaim(seed);
    runStore.claimDue("worker-2", NOW.plusSeconds(31), Duration.ofSeconds(30)).orElseThrow();
    assertFalse(
        transactions.complete(
            claimed, assistant("ok"), assistantCompleted(claimed), NOW.plusSeconds(31)));
  }

  // ---- appendExternalEvent unknown run ----

  @Test
  void appendExternalEventRejectsUnknownRun() {
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.appendExternalEvent(999_999_999L, assistantCompletedStale(), NOW));
  }

  private static RunEventDraft assistantCompletedStale() {
    return new RunEventDraft(
        RunEventType.ASSISTANT_COMPLETED, "{\"schemaVersion\":1,\"attempt\":1,\"turnIndex\":0}");
  }

  // ---- helpers ----

  private static ToolBinding toolBinding() {
    return ToolBinding.of(
        new ToolDescriptor(
            "read",
            "1",
            "read",
            null,
            new ToolParamsSchema("", Map.of(), Set.of(), true),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30)));
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static MessageEntryPayload assistant(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
        new AssistantMessageMetadata(
            ProviderStopReason.COMPLETED,
            new ModelUsage(1, 1, 0, 0, 0),
            new ModelCost("USD", BigDecimal.ZERO)));
  }

  private static MessageEntryPayload assistant(String text, List<ToolCall> calls) {
    AgentMessage msg;
    ProviderStopReason stopReason;
    if (calls.isEmpty()) {
      msg = new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
      stopReason = ProviderStopReason.COMPLETED;
    } else {
      List<AgentMessageContent> contents = new ArrayList<>();
      contents.add(new TextMessageContent(text));
      for (ToolCall call : calls) {
        contents.add(new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson()));
      }
      msg = new AgentMessage(AgentMessageRole.ASSISTANT, contents);
      stopReason = ProviderStopReason.TOOL_CALLS;
    }
    return new MessageEntryPayload(
        msg,
        new AssistantMessageMetadata(
            stopReason, new ModelUsage(1, 1, 0, 0, 0), new ModelCost("USD", BigDecimal.ZERO)));
  }

  private static RunEventDraft assistantCompleted(AgentRun run) {
    return new RunEventDraft(
        RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(run, "text", "ok"));
  }

  private record Seed(long sessionId, long snapshotId) {}
}
