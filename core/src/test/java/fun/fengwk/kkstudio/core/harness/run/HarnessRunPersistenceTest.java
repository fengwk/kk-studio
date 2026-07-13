package fun.fengwk.kkstudio.core.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRunPersistenceTest {
  private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort toolPreparation;
  @Autowired private SnowflakeRunIdGenerator idGenerator;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanHarnessRunTables() {
    jdbcTemplate.update("delete from harness_run_event");
    jdbcTemplate.update("delete from harness_run");
    jdbcTemplate.update("delete from harness_session_entry");
    jdbcTemplate.update("delete from harness_session");
  }

  /** API 事务提交后数据库同时具有 User Entry、QUEUED Run 与 activeRunId，无需内存 schedule。 */
  @Test
  void submitsUserEntryAndQueuedRunInOneTransaction() {
    Seed seed = seedSession();

    AgentRun run =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);

    Session stored = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(run.id(), stored.activeRunId());
    assertEquals(run.triggerEntryId(), stored.leafEntryId());
    assertEquals(RunStatus.QUEUED, runStore.find(run.id()).orElseThrow().status());
    SessionEntry trigger = sessionStore.find(seed.sessionId(), run.triggerEntryId()).orElseThrow();
    assertEquals(run.id(), trigger.runId());
    assertEquals("hello", text((MessageEntryPayload) trigger.payload()));
  }

  /** Session 行锁保证并发提交最多一个成功，失败事务不留下第二条 User Entry 或 Run。 */
  @Test
  void allowsOnlyOneActiveRunUnderConcurrentSubmission() throws Exception {
    Seed seed = seedSession();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger succeeded = new AtomicInteger();
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      int ordinal = i;
      futures.add(
          executor.submit(
              () -> {
                await(start);
                try {
                  transactions.submitUserMessage(
                      seed.sessionId(), seed.snapshotId(), user("message-" + ordinal), NOW);
                  succeeded.incrementAndGet();
                } catch (RuntimeException error) {
                  failures.add(error);
                }
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get();
    }
    executor.shutdownNow();

    assertEquals(1, succeeded.get());
    assertEquals(1, failures.size());
    Session stored = sessionStore.find(seed.sessionId()).orElseThrow();
    assertNotEquals(seed.snapshotId(), stored.leafEntryId());
    assertEquals(1, sessionStore.listChildren(seed.sessionId(), seed.snapshotId()).size());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_run where session_id = ?",
            Integer.class,
            seed.sessionId()));
  }

  /** claim 使用条件更新仲裁竞争，且 heartbeat 延长 lease、过期后其他 owner 可 reclaim 并增加 attempt。 */
  @Test
  void claimsOnceHeartbeatsAndReclaimsExpiredLease() throws Exception {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<AgentRun> first = executor.submit(() -> claim(start, "worker-a", NOW));
    Future<AgentRun> second = executor.submit(() -> claim(start, "worker-b", NOW));
    start.countDown();
    AgentRun claimedA = first.get();
    AgentRun claimedB = second.get();
    executor.shutdownNow();

    AgentRun claimed = claimedA == null ? claimedB : claimedA;
    assertTrue((claimedA == null) ^ (claimedB == null));
    assertEquals(queued.id(), claimed.id());
    assertEquals(1, claimed.attempt());
    assertTrue(
        runStore.heartbeat(
            claimed.id(),
            claimed.leaseOwner(),
            claimed.attempt(),
            NOW.plusSeconds(10),
            Duration.ofSeconds(30)));
    assertTrue(runStore.claimDue("early", NOW.plusSeconds(31), Duration.ofSeconds(30)).isEmpty());

    AgentRun reclaimed =
        runStore.claimDue("worker-c", NOW.plusSeconds(41), Duration.ofSeconds(30)).orElseThrow();
    assertEquals(2, reclaimed.attempt());
    assertEquals("worker-c", reclaimed.leaseOwner());
    assertFalse(
        runStore.heartbeat(
            claimed.id(),
            claimed.leaseOwner(),
            claimed.attempt(),
            NOW.plusSeconds(42),
            Duration.ofSeconds(30)));
  }

  /** RunEvent 并发 append 原子分配连续 sequence，cursor 只返回指定位置之后的事件。 */
  @Test
  void allocatesRunEventSequenceAtomicallyAndReadsByCursor() throws Exception {
    Seed seed = seedSession();
    AgentRun run =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    ExecutorService executor = Executors.newFixedThreadPool(6);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      int ordinal = i;
      futures.add(
          executor.submit(
              () -> {
                await(start);
                runStore.append(
                    run.id(),
                    RunEventType.TURN_STARTED,
                    "{\"schemaVersion\":1,\"ordinal\":" + ordinal + "}",
                    NOW.plusMillis(ordinal));
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get();
    }
    executor.shutdownNow();

    List<RunEvent> events = runStore.listAfter(run.id(), 5, 100);
    assertEquals(
        List.of(6L, 7L, 8L, 9L, 10L, 11L, 12L), events.stream().map(RunEvent::sequence).toList());
    assertEquals(12, runStore.find(run.id()).orElseThrow().eventSequence());
  }

  /** 完整 Assistant、SUCCEEDED 与 activeRunId 清理同事务提交，重复 finalize 不重复 Entry。 */
  @Test
  void completesNoToolRunExactlyOnce() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();
    MessageEntryPayload assistant = assistant("answer");

    assertTrue(transactions.complete(claimed, assistant, NOW.plusSeconds(1)));
    assertFalse(transactions.complete(claimed, assistant, NOW.plusSeconds(2)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, stored.status());
    assertEquals(1, stored.turnIndex());
    assertNull(session.activeRunId());
    assertEquals(1, sessionStore.listChildren(seed.sessionId(), queued.triggerEntryId()).size());
  }

  /** Tool barrier 只追加 Assistant 并进入 WAITING_TOOLS；T05 schema 中不存在 tool invocation 表。 */
  @Test
  void preparesToolBarrierWithoutForgingInvocation() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertTrue(
        toolPreparation.prepare(
            claimed,
            assistant("calling"),
            List.of(new ToolCall("call-1", "read", "{}")),
            NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.WAITING_TOOLS, stored.status());
    assertEquals(stored.id(), session.activeRunId());
    assertEquals(1, stored.turnIndex());
    assertFalse(tableExists("tool_invocation"));
  }

  /** Compaction Entry 追加后 requeue，且保留的 firstKeptEntry 必须位于活动路径。 */
  @Test
  void appendsCompactionAndRequeuesWithoutChangingOldEntries() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();
    CompactionEntryPayload compaction =
        new CompactionEntryPayload("summary", seed.snapshotId(), 100, "{}");

    assertTrue(
        transactions.compactAndRequeue(
            claimed, compaction, NOW.plusSeconds(1), NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    List<SessionEntry> path =
        sessionStore.loadPath(
            seed.sessionId(), sessionStore.find(seed.sessionId()).orElseThrow().leafEntryId());
    assertEquals(RunStatus.QUEUED, stored.status());
    assertEquals(3, path.size());
    assertEquals(compaction, path.get(2).payload());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session_entry where id = ?",
            Integer.class,
            seed.snapshotId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          AgentRun reclaimed =
              runStore
                  .claimDue("worker-2", NOW.plusSeconds(1), Duration.ofSeconds(30))
                  .orElseThrow();
          transactions.compactAndRequeue(
              reclaimed,
              new CompactionEntryPayload("bad", idGenerator.newSessionEntryId(), 100, "{}"),
              NOW.plusSeconds(2),
              NOW.plusSeconds(2));
        });
  }

  /** transient requeue 使用持久 nextAttemptAt；重新 claim 后 FAILED/CANCELLED terminal 清 activeRunId。 */
  @Test
  void requeuesAndTerminatesOwnedRuns() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun first = runStore.claimDue("worker-1", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertTrue(transactions.requeue(first, NOW.plusSeconds(5), NOW.plusSeconds(1)));
    assertTrue(runStore.claimDue("early", NOW.plusSeconds(4), Duration.ofSeconds(30)).isEmpty());
    AgentRun second =
        runStore.claimDue("worker-2", NOW.plusSeconds(5), Duration.ofSeconds(30)).orElseThrow();
    assertEquals(2, second.attempt());
    assertTrue(transactions.terminate(second, RunStatus.FAILED, NOW.plusSeconds(6)));
    assertFalse(transactions.terminate(second, RunStatus.FAILED, NOW.plusSeconds(7)));
    assertEquals(RunStatus.FAILED, runStore.find(queued.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());

    Seed cancelledSeed = seedSession();
    AgentRun cancelledQueued =
        transactions.submitUserMessage(
            cancelledSeed.sessionId(), cancelledSeed.snapshotId(), user("cancel"), NOW);
    assertTrue(runStore.requestCancel(cancelledQueued.id(), NOW.plusSeconds(1)));
    AgentRun cancelled =
        runStore.claimDue("worker-3", NOW.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
    assertEquals(NOW.plusSeconds(1), cancelled.cancelRequestedAt());
    assertTrue(transactions.terminate(cancelled, RunStatus.CANCELLED, NOW.plusSeconds(2)));
    assertEquals(RunStatus.CANCELLED, runStore.find(cancelled.id()).orElseThrow().status());
  }

  /** Store 与事务边界拒绝坏 owner/cursor/payload role、未知聚合和空 Tool barrier。 */
  @Test
  void rejectsInvalidRunOperations() {
    Seed seed = seedSession();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.submitUserMessage(
                seed.sessionId(), seed.snapshotId(), assistant("bad").message(), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.submitUserMessage(idGenerator.newRunId(), null, user("missing"), NOW));
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.complete(claimed, new MessageEntryPayload(user("bad")), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> toolPreparation.prepare(claimed, assistant("bad"), List.of(), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.terminate(claimed, RunStatus.SUCCEEDED, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> runStore.claimDue(" ", NOW, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> runStore.listAfter(queued.id(), -1, 1));
    assertThrows(IllegalArgumentException.class, () -> runStore.listAfter(queued.id(), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runStore.append(
                idGenerator.newRunId(), RunEventType.RUN_STARTED, "{\"schemaVersion\":1}", NOW));
  }

  /** 新 Run/Event 表遵循单 bigint id 最终契约并包含 lease/retry/cursor 字段。 */
  @Test
  void usesFinalRunAndEventColumns() {
    Set<String> runColumns = columns("harness_run");
    Set<String> eventColumns = columns("harness_run_event");

    assertEquals(
        Set.of(
            "id",
            "session_id",
            "trigger_entry_id",
            "status",
            "turn_index",
            "attempt",
            "event_sequence",
            "lease_owner",
            "lease_until",
            "next_attempt_at",
            "cancel_requested_at",
            "gmt_create",
            "started_at",
            "finished_at",
            "gmt_modified"),
        runColumns);
    assertEquals(
        Set.of("id", "run_id", "sequence", "event_type", "payload_json", "gmt_create"),
        eventColumns);
    assertFalse(runColumns.contains("run_id"));
  }

  /** Run 与 RunEvent 使用不同 Snowflake sequence，均为正 bigint。 */
  @Test
  void generatesSnowflakeRunIds() {
    long runId = idGenerator.newRunId();
    long eventId = idGenerator.newRunEventId();
    assertTrue(runId > 0);
    assertTrue(eventId > 0);
    assertNotEquals(runId, idGenerator.newRunId());
    assertNotEquals(eventId, idGenerator.newRunEventId());
  }

  private AgentRun claim(CountDownLatch start, String owner, Instant now) {
    await(start);
    return runStore.claimDue(owner, now, Duration.ofSeconds(30)).orElse(null);
  }

  private Seed seedSession() {
    long sessionId = idGenerator.newSessionEntryId();
    Session session = Session.root(sessionId, 1L, 1L, "run-test", false, NOW);
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

  private Set<String> columns(String table) {
    return Set.copyOf(
        jdbcTemplate.queryForList(
            "select column_name from information_schema.columns where table_name = ?",
            String.class,
            table));
  }

  private boolean tableExists(String table) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = ?",
            Integer.class,
            table);
    return count != null && count > 0;
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static MessageEntryPayload assistant(String text) {
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))));
  }

  private static String text(MessageEntryPayload payload) {
    return ((TextMessageContent) payload.message().contents().get(0)).text();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private record Seed(long sessionId, long snapshotId) {}
}
