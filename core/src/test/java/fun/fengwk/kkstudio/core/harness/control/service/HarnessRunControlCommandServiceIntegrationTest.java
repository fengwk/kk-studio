package fun.fengwk.kkstudio.core.harness.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.control.store.MysqlRunControlMessageStore;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T11-B2a 的核心闭环：active STEER/FOLLOW_UP 入队不改 leaf/run status；no-active FOLLOW_UP 走 direct
 * promotion；policy 来自不可变 AGENT_SNAPSHOT；no-active STEER conflict；事件/CAS 失败 rollback；terminal race
 * 与并发 enqueue 通过真实 CountDownLatch 验证。
 */
@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRunControlCommandServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

  @Autowired private HarnessRunControlCommandService commandService;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private MysqlRunControlMessageStore controlStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIdGen;
  @Autowired private SnowflakeRunIdGenerator runIdGen;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  @Test
  void activeSteerEnqueuesWithoutChangingLeafOrRunStatus() {
    Seed seed = seedSession("{\"steeringMode\":\"ONE_AT_A_TIME\"}");
    AgentRun claimed = submitAndClaim(seed);
    long leafBeforeControl = sessionStore.find(seed.sessionId()).orElseThrow().leafEntryId();

    RunControlMessage control =
        commandService.submit(
            seed.sessionId(), RunControlKind.STEER, user("please steer"), NOW.plusSeconds(1));

    assertEquals(RunControlStatus.PENDING, control.status());
    assertEquals(claimed.id(), control.originalRunId());
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, control.consumptionMode());

    // Session leaf/run status 未变
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(leafBeforeControl, session.leafEntryId());
    assertEquals(RunStatus.RUNNING, runStore.find(claimed.id()).orElseThrow().status());

    // 持久 row 存在且 status=PENDING
    RunControlMessage stored = controlStore.find(control.id()).orElseThrow();
    assertEquals(RunControlStatus.PENDING, stored.status());
    assertNull(stored.consumedRunId());

    // 原 Run journal 追加了 STEER_REQUESTED，没有其它事件
    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    assertEquals(1, events.size());
    assertEquals(RunEventType.STEER_REQUESTED, events.get(0).type());
    assertTrue(events.get(0).payloadJson().contains("\"controlId\":" + control.id()));
    assertTrue(events.get(0).payloadJson().contains("\"kind\":\"STEER\""));
    assertTrue(events.get(0).payloadJson().contains("\"consumptionMode\":\"ONE_AT_A_TIME\""));
  }

  @Test
  void activeFollowUpEnqueuesWithoutChangingLeafOrRunStatus() {
    Seed seed = seedSession("{\"followUpMode\":\"ALL\"}");
    AgentRun claimed = submitAndClaim(seed);
    long leafBeforeControl = sessionStore.find(seed.sessionId()).orElseThrow().leafEntryId();

    RunControlMessage control =
        commandService.submit(
            seed.sessionId(), RunControlKind.FOLLOW_UP, user("queue please"), NOW.plusSeconds(1));

    assertEquals(RunControlStatus.PENDING, control.status());
    assertEquals(claimed.id(), control.originalRunId());
    assertEquals(ControlConsumptionMode.ALL, control.consumptionMode());

    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(leafBeforeControl, session.leafEntryId());
    assertEquals(RunStatus.RUNNING, runStore.find(claimed.id()).orElseThrow().status());

    List<RunEvent> events = runStore.listAfter(claimed.id(), 0, 100);
    assertEquals(1, events.size());
    assertEquals(RunEventType.FOLLOW_UP_REQUESTED, events.get(0).type());
    assertTrue(events.get(0).payloadJson().contains("\"kind\":\"FOLLOW_UP\""));
    assertTrue(events.get(0).payloadJson().contains("\"consumptionMode\":\"ALL\""));
  }

  @Test
  void frozenPolicyIsDecidedByLatestSnapshotAndIsImmutable() {
    Seed seed = seedSession("{\"steeringMode\":\"ONE_AT_A_TIME\"}");
    AgentRun claimed = submitAndClaim(seed);
    long leafBefore = sessionStore.find(seed.sessionId()).orElseThrow().leafEntryId();

    // 入队时 policy 来自历史 snapshot（ONE_AT_A_TIME）
    RunControlMessage first =
        commandService.submit(
            seed.sessionId(), RunControlKind.STEER, user("first"), NOW.plusSeconds(1));
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, first.consumptionMode());

    // 后续追加一个 ALL 的新 snapshot 也不影响既有的 PENDING row（frozen 语义）
    appendSnapshot(seed.sessionId(), leafBefore, "{\"steeringMode\":\"ALL\"}");
    RunControlMessage firstReloaded = controlStore.find(first.id()).orElseThrow();
    assertEquals(ControlConsumptionMode.ONE_AT_A_TIME, firstReloaded.consumptionMode());
    assertEquals(RunControlStatus.PENDING, firstReloaded.status());
    assertNotNull(runStore.find(claimed.id()).orElseThrow());

    // 新入队按新 snapshot 走 ALL
    RunControlMessage second =
        commandService.submit(
            seed.sessionId(), RunControlKind.STEER, user("second"), NOW.plusSeconds(2));
    assertEquals(ControlConsumptionMode.ALL, second.consumptionMode());
  }

  @Test
  void noActiveSteerConflictsAndLeavesNoResidue() {
    Seed seed = seedSession("{}");
    // 不创建 active run
    RunControlConflictException error =
        assertThrows(
            RunControlConflictException.class,
            () ->
                commandService.submit(
                    seed.sessionId(),
                    RunControlKind.STEER,
                    user("nobody home"),
                    NOW.plusSeconds(1)));
    assertEquals(RunControlKind.STEER, error.kind());

    // 验证无 PENDING 残留、无新 Entry、无新 Run
    assertEquals(0L, countControl(seed.sessionId()));
    assertEquals(1L, countEntries(seed.sessionId()));
    assertEquals(0L, countRuns(seed.sessionId()));
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertNull(session.activeRunId());
    assertEquals(seed.snapshotId(), session.leafEntryId());
  }

  @Test
  void noActiveFollowUpDirectPromotes() {
    Seed seed = seedSession("{\"followUpMode\":\"ALL\"}");

    RunControlMessage control =
        commandService.submit(
            seed.sessionId(), RunControlKind.FOLLOW_UP, user("promote me"), NOW.plusSeconds(1));

    assertEquals(RunControlStatus.PROMOTED, control.status());
    assertNull(control.originalRunId());
    assertNotNull(control.consumedRunId());
    assertNotNull(control.consumedEntryId());
    assertEquals(ControlConsumptionMode.ALL, control.consumptionMode());
    assertNotNull(control.consumedAt());

    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(control.consumedRunId(), session.activeRunId());
    assertEquals(control.consumedEntryId(), session.leafEntryId());

    AgentRun newRun = runStore.find(control.consumedRunId()).orElseThrow();
    assertEquals(RunStatus.QUEUED, newRun.status());
    assertEquals(control.consumedEntryId(), newRun.triggerEntryId());

    // 仅一个新 USER Entry（除了 snapshot）
    assertEquals(2L, countEntries(seed.sessionId()));

    // 新 Run journal 顺序：FOLLOW_UP_REQUESTED + CONTROL_PROMOTED
    List<RunEvent> events = runStore.listAfter(newRun.id(), 0, 100);
    assertEquals(2, events.size());
    assertEquals(RunEventType.FOLLOW_UP_REQUESTED, events.get(0).type());
    assertEquals(RunEventType.CONTROL_PROMOTED, events.get(1).type());
    assertTrue(events.get(1).payloadJson().contains("\"targetRunId\":" + newRun.id()));
    assertTrue(events.get(1).payloadJson().contains("\"entryId\":" + control.consumedEntryId()));
  }

  @Test
  void followUpAfterCompletedRunPromotes() {
    Seed seed = seedSession("{}");
    AgentRun claimed = submitAndClaim(seed);

    assertTrue(
        transactions.complete(
            claimed, assistant("done"), assistantCompleted(claimed), NOW.plusSeconds(2)));
    assertEquals(RunStatus.SUCCEEDED, runStore.find(claimed.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());

    RunControlMessage control =
        commandService.submit(
            seed.sessionId(), RunControlKind.FOLLOW_UP, user("after terminal"), NOW.plusSeconds(3));

    assertEquals(RunControlStatus.PROMOTED, control.status());
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertNotNull(session.activeRunId());
    assertNotEquals(claimed.id(), session.activeRunId());
    assertEquals(4L, countEntries(seed.sessionId()));
  }

  @Test
  void activeSteerConflictsWhenHintRunIsTerminalUnderLock() {
    Seed seed = seedSession("{}");
    AgentRun claimed = submitAndClaim(seed);

    // hint 仍指 claimed，但锁后 run 已被外部标 terminal -> STEER conflict
    jdbc.update(
        "update harness_run set status='SUCCEEDED', lease_owner=null, lease_until=null,"
            + " finished_at=? where id=?",
        NOW.plusSeconds(1),
        claimed.id());
    // 注意：session.active_run_id 不动，制造 hint stale、locked run 已 terminal 的状态

    RunControlConflictException error =
        assertThrows(
            RunControlConflictException.class,
            () ->
                commandService.submit(
                    seed.sessionId(), RunControlKind.STEER, user("x"), NOW.plusSeconds(2)));
    assertEquals(RunControlKind.STEER, error.kind());

    // 没有写入任何 PENDING row（事务回滚）
    assertEquals(0L, countControl(seed.sessionId()));
  }

  @Test
  void activeControlsConflictWhenCancelRequestedUnderLock() {
    Seed seed = seedSession("{}");
    AgentRun claimed = submitAndClaim(seed);

    jdbc.update(
        "update harness_run set cancel_requested_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(1)),
        claimed.id());

    for (RunControlKind kind : RunControlKind.values()) {
      RunControlConflictException error =
          assertThrows(
              RunControlConflictException.class,
              () ->
                  commandService.submit(
                      seed.sessionId(), kind, user("after cancel"), NOW.plusSeconds(2)));
      assertEquals(kind, error.kind());
    }
    assertEquals(0L, countControl(seed.sessionId()));
    assertEquals(claimed.id(), sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
  }

  @Test
  void activeSteerConflictsWhenHintRunDeletedUnderLock() {
    Seed seed = seedSession("{}");
    AgentRun claimed = submitAndClaim(seed);

    // 把 run 直接删掉：锁后 lockedRun == null，session.active_run_id 仍指向 deleted run。
    jdbc.update("delete from harness_run where id=?", claimed.id());

    assertThrows(
        RunControlConflictException.class,
        () ->
            commandService.submit(
                seed.sessionId(), RunControlKind.STEER, user("missing"), NOW.plusSeconds(2)));
    assertEquals(0L, countControl(seed.sessionId()));
  }

  @Test
  void noAgentSnapshotOnPathIsRejected() {
    long sessionId = sessionIdGen.newSessionId();
    Session session = Session.root(sessionId, 1L, "no-snapshot", false, NOW);
    sessionStore.create(session);
    long messageEntryId = sessionIdGen.newEntryId();
    MessageEntryPayload message = new MessageEntryPayload(user("message without snapshot"));
    sessionStore.append(
        new SessionEntry(messageEntryId, sessionId, null, null, message.type(), message, NOW),
        null,
        0L);

    assertThrows(
        IllegalStateException.class,
        () ->
            commandService.submit(
                sessionId, RunControlKind.FOLLOW_UP, user("x"), NOW.plusSeconds(1)));
    assertEquals(0L, countControl(sessionId));
    assertEquals(1L, countEntries(sessionId));
    assertEquals(0L, countRuns(sessionId));
  }

  @Test
  void unknownSessionRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            commandService.submit(
                999_999_999L, RunControlKind.STEER, user("x"), NOW.plusSeconds(1)));
  }

  @Test
  void nonUserMessageRejected() {
    Seed seed = seedSession("{}");
    AgentRun claimed = submitAndClaim(seed);
    AgentMessage assistant =
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("hi")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            commandService.submit(
                seed.sessionId(), RunControlKind.STEER, assistant, NOW.plusSeconds(1)));
    assertEquals(RunStatus.RUNNING, runStore.find(claimed.id()).orElseThrow().status());
    assertEquals(0L, countControl(seed.sessionId()));
  }

  @Test
  void concurrentFollowUpEnqueueWithTerminalRaceSettlesToExactlyOneOutcome() throws Exception {
    Seed seed = seedSession("{\"followUpMode\":\"ALL\"}");
    AgentRun claimed = submitAndClaim(seed);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Future<RunControlMessage> followUpFuture =
        pool.submit(
            () -> {
              ready.countDown();
              start.await();
              return commandService.submit(
                  seed.sessionId(), RunControlKind.FOLLOW_UP, user("race"), NOW.plusSeconds(10));
            });

    Future<Boolean> completion =
        pool.submit(
            () -> {
              ready.countDown();
              start.await();
              return transactions.complete(
                  claimed,
                  assistant("race answer"),
                  assistantCompleted(claimed),
                  NOW.plusSeconds(10));
            });

    ready.await(5, TimeUnit.SECONDS);
    start.countDown();

    try {
      RunControlMessage accepted = followUpFuture.get(5, TimeUnit.SECONDS);
      assertTrue(completion.get(5, TimeUnit.SECONDS));

      RunControlMessage settled = controlStore.find(accepted.id()).orElseThrow();
      Session session = sessionStore.find(seed.sessionId()).orElseThrow();
      assertEquals(0L, countPendingControls(seed.sessionId()));
      assertEquals(1L, countEntriesContaining(seed.sessionId(), "race"));
      assertEquals(4L, countEntries(seed.sessionId()));

      if (settled.status() == RunControlStatus.CONSUMED) {
        assertEquals(claimed.id(), settled.consumedRunId());
        assertEquals(claimed.id(), session.activeRunId());
        assertEquals(RunStatus.QUEUED, runStore.find(claimed.id()).orElseThrow().status());
      } else {
        assertEquals(RunControlStatus.PROMOTED, settled.status());
        assertEquals(RunStatus.SUCCEEDED, runStore.find(claimed.id()).orElseThrow().status());
        assertEquals(settled.consumedRunId(), session.activeRunId());
        assertEquals(
            RunStatus.QUEUED, runStore.find(settled.consumedRunId()).orElseThrow().status());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- helpers ----

  private Seed seedSession(String policyJson) {
    long sessionId = sessionIdGen.newSessionId();
    Session session = Session.root(sessionId, 1L, "control-cmd-test", false, NOW);
    sessionStore.create(session);
    long snapshotId = runIdGen.newSessionEntryId();
    AgentSnapshot snapshot =
        new AgentSnapshot(
            "system", "model-default", "default", List.of(), List.of(), List.of(), policyJson);
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, payload.type(), payload, NOW),
        null,
        0L);
    return new Seed(sessionId, snapshotId);
  }

  private void appendSnapshot(long sessionId, long parentEntryId, String policyJson) {
    long entryId = runIdGen.newSessionEntryId();
    AgentSnapshot snapshot =
        new AgentSnapshot(
            "system", "model-default", "default", List.of(), List.of(), List.of(), policyJson);
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    sessionStore.append(
        new SessionEntry(entryId, sessionId, parentEntryId, null, payload.type(), payload, NOW),
        parentEntryId,
        sessionStore.find(sessionId).orElseThrow().version());
  }

  private AgentRun submitAndClaim(Seed seed) {
    AgentRun queued =
        transactions.submitUserMessage(
            seed.sessionId(), seed.snapshotId(), user("initial"), NOW.plusSeconds(0));
    return runStore.claimDue("worker", NOW.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
  }

  private long countControl(long sessionId) {
    Long c =
        jdbc.queryForObject(
            "select count(*) from harness_run_control_message where session_id=?",
            Long.class,
            sessionId);
    return c == null ? 0L : c;
  }

  private long countPendingControls(long sessionId) {
    Long c =
        jdbc.queryForObject(
            "select count(*) from harness_run_control_message where session_id=? and"
                + " status='PENDING'",
            Long.class,
            sessionId);
    return c == null ? 0L : c;
  }

  private long countEntries(long sessionId) {
    Long c =
        jdbc.queryForObject(
            "select count(*) from harness_session_entry where session_id=?", Long.class, sessionId);
    return c == null ? 0L : c;
  }

  private long countEntriesContaining(long sessionId, String text) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_session_entry where session_id=? and payload_json like ?",
            Long.class,
            sessionId,
            "%\"text\":\"" + text + "\"%");
    return count == null ? 0L : count;
  }

  private long countRuns(long sessionId) {
    Long c =
        jdbc.queryForObject(
            "select count(*) from harness_run where session_id=?", Long.class, sessionId);
    return c == null ? 0L : c;
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

  private static RunEventDraft assistantCompleted(AgentRun run) {
    return new RunEventDraft(
        RunEventType.ASSISTANT_COMPLETED,
        RunEventPayloads.forAttempt(run, "stopReason", ProviderStopReason.COMPLETED.name()));
  }

  private record Seed(long sessionId, long snapshotId) {}
}
