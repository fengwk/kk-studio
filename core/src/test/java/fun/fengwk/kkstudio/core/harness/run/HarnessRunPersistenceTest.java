package fun.fengwk.kkstudio.core.harness.run;

import static fun.fengwk.kkstudio.core.harness.HarnessUsageFixtures.completedUsageDraft;
import static fun.fengwk.kkstudio.core.harness.HarnessUsageFixtures.toolCallsUsageDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.run.store.SnowflakeRunIdGenerator;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.task.store.DatabaseRootActivityStore;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
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
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(classes = CoreTestApplication.class)
class HarnessRunPersistenceTest {
  private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort toolPreparation;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private DatabaseRootActivityStore rootActivityStore;
  @Autowired private SnowflakeRunIdGenerator idGenerator;
  @Autowired private HarnessRunMapper runMapper;
  @Autowired private HarnessRunEventMapper eventMapper;
  @Autowired private HarnessSessionMapper sessionMapper;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanHarnessRunTables() {
    jdbcTemplate.update("delete from model_usage_record");
    jdbcTemplate.update("delete from tool_invocation");
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

  /** 高竞争下每次 CAS 失败都会重读新候选，一个已失败候选不会饿死其后的 due Run。 */
  @Test
  void claimsAllDueRunsWithoutCandidateStarvationUnderContention() throws Exception {
    int runCount = 24;
    Set<Long> queuedRunIds = new HashSet<>();
    for (int i = 0; i < runCount; i++) {
      Seed seed = seedSession();
      queuedRunIds.add(
          transactions
              .submitUserMessage(seed.sessionId(), seed.snapshotId(), user("run-" + i), NOW)
              .id());
    }
    ExecutorService executor = Executors.newFixedThreadPool(runCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<AgentRun>> futures = new ArrayList<>();
    for (int i = 0; i < runCount; i++) {
      String owner = "contender-" + i;
      futures.add(executor.submit(() -> claim(start, owner, NOW)));
    }

    start.countDown();
    Set<Long> claimedRunIds = new HashSet<>();
    for (Future<AgentRun> future : futures) {
      AgentRun claimed = future.get();
      assertTrue(claimed != null);
      claimedRunIds.add(claimed.id());
    }
    executor.shutdownNow();

    assertEquals(queuedRunIds, claimedRunIds);
    assertEquals(runCount, claimedRunIds.size());
  }

  /**
   * Root lock prevents a sibling Run from allocating its event id until the first writer commits.
   */
  @Test
  void concurrentSiblingRunAppendsPreserveRootActivityCursor() throws Exception {
    Seed root = seedSession();
    Seed child = seedChild(root.sessionId());
    AgentRun runA =
        transactions.submitUserMessage(root.sessionId(), root.snapshotId(), user("a"), NOW);
    AgentRun runB =
        transactions.submitUserMessage(child.sessionId(), child.snapshotId(), user("b"), NOW);

    BlockingRunEventIdGenerator blockingIds = new BlockingRunEventIdGenerator();
    HarnessRunEventWriter writer =
        new HarnessRunEventWriter(runMapper, eventMapper, sessionMapper, blockingIds);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    boolean allocatedBeforeFirstCommit = false;
    try {
      Future<?> first =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      ignored ->
                          writer.lockAndAppend(
                              runA.id(),
                              List.of(
                                  new RunEventDraft(
                                      RunEventType.TURN_STARTED, "{\"schemaVersion\":1}")),
                              NOW)));
      assertTrue(blockingIds.firstGenerated.await(5, TimeUnit.SECONDS));
      Future<?> second =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      ignored -> {
                        runMapper.findForUpdate(runB.id());
                        sessionMapper.findForUpdate(runB.sessionId());
                        blockingIds.secondStarted.countDown();
                        writer.lockAndAppend(
                            runB.id(),
                            List.of(
                                new RunEventDraft(
                                    RunEventType.TURN_STARTED, "{\"schemaVersion\":1}")),
                            NOW);
                      }));
      assertTrue(blockingIds.secondStarted.await(5, TimeUnit.SECONDS));
      allocatedBeforeFirstCommit = blockingIds.secondGenerated.await(250, TimeUnit.MILLISECONDS);
      blockingIds.releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      blockingIds.releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertFalse(allocatedBeforeFirstCommit);
    assertTrue(blockingIds.secondGenerated.await(5, TimeUnit.SECONDS));
    List<RootActivity> committed = rootActivityStore.list(root.sessionId(), 0, 10);
    assertEquals(
        List.of(blockingIds.firstId, blockingIds.secondId),
        committed.stream().map(RootActivity::eventId).toList());
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

    assertTrue(
        transactions.complete(
            claimed,
            assistant,
            completedUsageDraft(),
            assistantCompleted(claimed),
            NOW.plusSeconds(1)));
    assertFalse(
        transactions.complete(
            claimed,
            assistant,
            completedUsageDraft(),
            assistantCompleted(claimed),
            NOW.plusSeconds(2)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.SUCCEEDED, stored.status());
    assertEquals(1, stored.turnIndex());
    assertNull(session.activeRunId());
    List<SessionEntry> children =
        sessionStore.listChildren(seed.sessionId(), queued.triggerEntryId());
    assertEquals(1, children.size());
    assertEquals(assistant, children.get(0).payload());
    assertEquals(
        List.of(RunEventType.ASSISTANT_COMPLETED, RunEventType.RUN_COMPLETED),
        runStore.listAfter(queued.id(), 0, 10).stream().map(RunEvent::type).toList());
  }

  /** terminal event 写入失败时 Entry、状态和 activeRunId 全部回滚，不存在 terminal crash gap。 */
  @Test
  void rollsBackTerminalTransitionWhenTerminalEventCannotBePersisted() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.complete(
                claimed,
                assistant("answer"),
                completedUsageDraft(),
                new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, "{}"),
                NOW.plusSeconds(1)));

    AgentRun afterRollback = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.RUNNING, afterRollback.status());
    assertEquals(claimed.leaseOwner(), afterRollback.leaseOwner());
    assertEquals(queued.id(), session.activeRunId());
    assertEquals(queued.triggerEntryId(), session.leafEntryId());
    assertTrue(runStore.listAfter(queued.id(), 0, 10).isEmpty());
  }

  /** Assistant、source-order Invocation、WAITING_TOOLS 与事件必须在同一事务提交。 */
  @Test
  void preparesAssistantAndInvocationAtomically() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();
    ToolCall call = new ToolCall("call-1", "read", "{}");

    assertTrue(
        toolPreparation.prepare(
            claimed,
            assistant("calling", List.of(call)),
            toolCallsUsageDraft(),
            List.of(call),
            List.of(readBinding()),
            Path.of("."),
            Path.of("."),
            List.of(terminalEvent(claimed, RunEventType.ASSISTANT_COMPLETED)),
            NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.WAITING_TOOLS, stored.status());
    assertEquals(stored.id(), session.activeRunId());
    assertEquals(1, stored.turnIndex());
    assertEquals(
        List.of(
            RunEventType.ASSISTANT_COMPLETED, RunEventType.TOOL_PREPARED, RunEventType.RUN_WAITING),
        runStore.listAfter(queued.id(), 0, 10).stream().map(RunEvent::type).toList());
    String preparedPayload =
        runStore.listAfter(queued.id(), 0, 10).stream()
            .filter(event -> event.type() == RunEventType.TOOL_PREPARED)
            .findFirst()
            .orElseThrow()
            .payloadJson();
    assertTrue(preparedPayload.contains("\"arguments\":\"{}\""), preparedPayload);
    assertEquals(
        assistant("calling", List.of(call)),
        sessionStore.find(seed.sessionId(), session.leafEntryId()).orElseThrow().payload());
    assertTrue(tableExists("tool_invocation"));
    assertEquals(
        ToolInvocationStatus.QUEUED, invocationStore.listByRun(stored.id()).get(0).status());
    assertEquals(0, invocationStore.listByRun(stored.id()).get(0).ordinal());
  }

  /** Tool barrier event 失败时 Assistant、WAITING_TOOLS 与全部 barrier events 一起回滚。 */
  @Test
  void rollsBackToolBarrierWhenBarrierEventCannotBePersisted() {
    Seed seed = seedSession();
    AgentRun queued =
        transactions.submitUserMessage(seed.sessionId(), seed.snapshotId(), user("hello"), NOW);
    AgentRun claimed = runStore.claimDue("worker", NOW, Duration.ofSeconds(30)).orElseThrow();
    ToolCall call = new ToolCall("call-1", "read", "{}");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolPreparation.prepare(
                claimed,
                assistant("calling", List.of(call)),
                toolCallsUsageDraft(),
                List.of(call),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, "{}")),
                NOW.plusSeconds(1)));

    AgentRun afterRollback = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.RUNNING, afterRollback.status());
    assertEquals(claimed.leaseOwner(), afterRollback.leaseOwner());
    assertEquals(queued.id(), session.activeRunId());
    assertEquals(queued.triggerEntryId(), session.leafEntryId());
    assertTrue(runStore.listAfter(queued.id(), 0, 10).isEmpty());
    assertTrue(invocationStore.listByRun(queued.id()).isEmpty());
  }

  /** retry、compaction、FAILED 的 event 批次失败时状态、leaf、activeRunId 与已插事件全部回滚。 */
  @Test
  void rollsBackRetryCompactionAndTerminationWhenEventsCannotBePersisted() {
    Seed retrySeed = seedSession();
    AgentRun retryQueued =
        transactions.submitUserMessage(
            retrySeed.sessionId(), retrySeed.snapshotId(), user("retry"), NOW);
    AgentRun retryClaimed =
        runStore.claimDue("retry-worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.requeue(
                retryClaimed,
                NOW.plusSeconds(5),
                eventsEndingMalformed(retryClaimed, RunEventType.ASSISTANT_FAILED),
                NOW.plusSeconds(1)));

    assertOwnedRunningWithoutEvents(retrySeed, retryQueued, retryClaimed);

    Seed compactionSeed = seedSession();
    AgentRun compactionQueued =
        transactions.submitUserMessage(
            compactionSeed.sessionId(), compactionSeed.snapshotId(), user("compaction"), NOW);
    AgentRun compactionClaimed =
        runStore.claimDue("compaction-worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.compactAndRequeue(
                compactionClaimed,
                new CompactionEntryPayload("summary", compactionSeed.snapshotId(), 100, "{}"),
                NOW.plusSeconds(1),
                eventsEndingMalformed(compactionClaimed, RunEventType.COMPACTION_STARTED),
                NOW.plusSeconds(1)));

    assertOwnedRunningWithoutEvents(compactionSeed, compactionQueued, compactionClaimed);
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session_entry where run_id = ?",
            Integer.class,
            compactionQueued.id()));

    Seed failedSeed = seedSession();
    AgentRun failedQueued =
        transactions.submitUserMessage(
            failedSeed.sessionId(), failedSeed.snapshotId(), user("failed"), NOW);
    AgentRun failedClaimed =
        runStore.claimDue("failed-worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.terminate(
                failedClaimed,
                RunStatus.FAILED,
                eventsEndingMalformed(failedClaimed, RunEventType.ASSISTANT_FAILED),
                NOW.plusSeconds(1)));

    assertOwnedRunningWithoutEvents(failedSeed, failedQueued, failedClaimed);
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
            claimed,
            compaction,
            NOW.plusSeconds(1),
            List.of(
                terminalEvent(claimed, RunEventType.ASSISTANT_FAILED),
                terminalEvent(claimed, RunEventType.COMPACTION_STARTED),
                terminalEvent(claimed, RunEventType.COMPACTION_COMPLETED),
                terminalEvent(claimed, RunEventType.RETRY_SCHEDULED)),
            NOW.plusSeconds(1)));

    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    List<SessionEntry> path =
        sessionStore.loadPath(
            seed.sessionId(), sessionStore.find(seed.sessionId()).orElseThrow().leafEntryId());
    assertEquals(RunStatus.QUEUED, stored.status());
    assertEquals(3, path.size());
    assertEquals(compaction, path.get(2).payload());
    assertEquals(
        List.of(
            RunEventType.ASSISTANT_FAILED,
            RunEventType.COMPACTION_STARTED,
            RunEventType.COMPACTION_COMPLETED,
            RunEventType.RETRY_SCHEDULED),
        runStore.listAfter(queued.id(), 0, 10).stream().map(RunEvent::type).toList());
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
              List.of(terminalEvent(reclaimed, RunEventType.RETRY_SCHEDULED)),
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

    assertTrue(
        transactions.requeue(
            first,
            NOW.plusSeconds(5),
            List.of(
                terminalEvent(first, RunEventType.ASSISTANT_FAILED),
                terminalEvent(first, RunEventType.RETRY_SCHEDULED)),
            NOW.plusSeconds(1)));
    assertTrue(runStore.claimDue("early", NOW.plusSeconds(4), Duration.ofSeconds(30)).isEmpty());
    AgentRun second =
        runStore.claimDue("worker-2", NOW.plusSeconds(5), Duration.ofSeconds(30)).orElseThrow();
    assertEquals(2, second.attempt());
    List<RunEventDraft> failedEvents =
        List.of(
            terminalEvent(second, RunEventType.ASSISTANT_FAILED),
            terminalEvent(second, RunEventType.RUN_FAILED));
    assertTrue(transactions.terminate(second, RunStatus.FAILED, failedEvents, NOW.plusSeconds(6)));
    assertFalse(transactions.terminate(second, RunStatus.FAILED, failedEvents, NOW.plusSeconds(7)));
    assertEquals(RunStatus.FAILED, runStore.find(queued.id()).orElseThrow().status());
    assertNull(sessionStore.find(seed.sessionId()).orElseThrow().activeRunId());
    assertEquals(
        List.of(
            RunEventType.ASSISTANT_FAILED,
            RunEventType.RETRY_SCHEDULED,
            RunEventType.ASSISTANT_FAILED,
            RunEventType.RUN_FAILED),
        runStore.listAfter(queued.id(), 0, 10).stream().map(RunEvent::type).toList());

    Seed cancelledSeed = seedSession();
    AgentRun cancelledQueued =
        transactions.submitUserMessage(
            cancelledSeed.sessionId(), cancelledSeed.snapshotId(), user("cancel"), NOW);
    assertTrue(runStore.requestCancel(cancelledQueued.id(), NOW.plusSeconds(1)));
    AgentRun cancelled =
        runStore.claimDue("worker-3", NOW.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
    assertEquals(NOW.plusSeconds(1), cancelled.cancelRequestedAt());
    assertTrue(
        transactions.terminate(
            cancelled,
            RunStatus.CANCELLED,
            List.of(terminalEvent(cancelled, RunEventType.RUN_CANCELLED)),
            NOW.plusSeconds(2)));
    assertEquals(RunStatus.CANCELLED, runStore.find(cancelled.id()).orElseThrow().status());
    assertEquals(
        List.of(RunEventType.RUN_CANCELLED),
        runStore.listAfter(cancelled.id(), 0, 10).stream().map(RunEvent::type).toList());
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
        () ->
            transactions.complete(
                claimed,
                new MessageEntryPayload(user("bad")),
                completedUsageDraft(),
                new RunEventDraft(RunEventType.ASSISTANT_COMPLETED, "{}"),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.complete(
                claimed,
                assistant("bad", List.of(new ToolCall("unexpected", "read", "{}"))),
                completedUsageDraft(),
                assistantCompleted(claimed),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolPreparation.prepare(
                claimed,
                assistant("bad"),
                toolCallsUsageDraft(),
                List.of(new ToolCall("missing-from-assistant", "read", "{}")),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(terminalEvent(claimed, RunEventType.RUN_WAITING)),
                NOW));
    ToolCall duplicateEventCall = new ToolCall("duplicate-event", "read", "{}");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolPreparation.prepare(
                claimed,
                assistant("bad", List.of(duplicateEventCall)),
                toolCallsUsageDraft(),
                List.of(duplicateEventCall),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(
                    terminalEvent(claimed, RunEventType.ASSISTANT_COMPLETED),
                    terminalEvent(claimed, RunEventType.ASSISTANT_COMPLETED)),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            toolPreparation.prepare(
                claimed,
                assistant("bad"),
                toolCallsUsageDraft(),
                List.of(),
                List.of(readBinding()),
                Path.of("."),
                Path.of("."),
                List.of(terminalEvent(claimed, RunEventType.RUN_WAITING)),
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.terminate(
                claimed,
                RunStatus.SUCCEEDED,
                List.of(terminalEvent(claimed, RunEventType.RUN_COMPLETED)),
                NOW));
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
    Set<String> invocationColumns = columns("tool_invocation");

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
    assertEquals(
        Set.of(
            "id",
            "run_id",
            "assistant_entry_id",
            "ordinal",
            "tool_call_id",
            "tool_name",
            "tool_version",
            "target_type",
            "environment_id",
            "arguments_json",
            "status",
            "permission_action",
            "permission_decision",
            "side_effect",
            "deadline_at",
            "lease_owner",
            "lease_until",
            "cancel_requested_at",
            "result_json",
            "error_message",
            "gmt_create",
            "started_at",
            "finished_at",
            "gmt_modified"),
        invocationColumns);
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
    Session session = Session.root(sessionId, 1L, "run-test", false, NOW);
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

  private Seed seedChild(long rootSessionId) {
    long sessionId = idGenerator.newSessionEntryId();
    Session child =
        new Session(
            sessionId,
            1L,
            "child-run-test",
            null,
            null,
            rootSessionId,
            rootSessionId,
            null,
            1,
            false,
            0,
            NOW,
            NOW);
    sessionStore.createFork(child, List.of());
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

  private void assertOwnedRunningWithoutEvents(Seed seed, AgentRun queued, AgentRun claimed) {
    AgentRun stored = runStore.find(queued.id()).orElseThrow();
    Session session = sessionStore.find(seed.sessionId()).orElseThrow();
    assertEquals(RunStatus.RUNNING, stored.status());
    assertEquals(claimed.leaseOwner(), stored.leaseOwner());
    assertEquals(0, stored.eventSequence());
    assertEquals(queued.id(), session.activeRunId());
    assertEquals(queued.triggerEntryId(), session.leafEntryId());
    assertTrue(runStore.listAfter(queued.id(), 0, 10).isEmpty());
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
    return assistant(text, List.of());
  }

  private static MessageEntryPayload assistant(String text, List<ToolCall> calls) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent(text));
    calls.forEach(
        call ->
            contents.add(
                new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson())));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            calls.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS,
            new ModelUsage(10, 2, 1, 0, 0, 0, 13),
            new ModelCost(
                "USD",
                new BigDecimal("0.000012"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("0.000012"))));
  }

  private static ToolBinding readBinding() {
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

  private static RunEventDraft assistantCompleted(AgentRun run) {
    return new RunEventDraft(
        RunEventType.ASSISTANT_COMPLETED,
        "{\"schemaVersion\":1,\"attempt\":"
            + run.attempt()
            + ",\"turnIndex\":"
            + run.turnIndex()
            + "}");
  }

  private static RunEventDraft terminalEvent(AgentRun run, RunEventType type) {
    return new RunEventDraft(
        type,
        "{\"schemaVersion\":1,\"attempt\":"
            + run.attempt()
            + ",\"turnIndex\":"
            + run.turnIndex()
            + "}");
  }

  private static List<RunEventDraft> eventsEndingMalformed(AgentRun run, RunEventType type) {
    return List.of(terminalEvent(run, type), new RunEventDraft(type, "{}"));
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

  private static final class BlockingRunEventIdGenerator implements RunIdGenerator {
    private final long firstId = Long.MAX_VALUE - 10;
    private final long secondId = Long.MAX_VALUE - 9;
    private final CountDownLatch firstGenerated = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CountDownLatch secondStarted = new CountDownLatch(1);
    private final CountDownLatch secondGenerated = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public long newRunId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long newRunEventId() {
      int call = calls.incrementAndGet();
      if (call == 1) {
        firstGenerated.countDown();
        await(releaseFirst);
        return firstId;
      }
      if (call == 2) {
        secondGenerated.countDown();
        return secondId;
      }
      throw new AssertionError("unexpected event id allocation: " + call);
    }

    @Override
    public long newSessionEntryId() {
      throw new UnsupportedOperationException();
    }
  }

  private record Seed(long sessionId, long snapshotId) {}
}
