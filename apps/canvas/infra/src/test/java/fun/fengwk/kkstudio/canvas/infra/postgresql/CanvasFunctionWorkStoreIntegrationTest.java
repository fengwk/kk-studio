package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;

import fun.fengwk.kkstudio.canvas.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRunTransactions;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionWorker;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.canvas.infra.function.FixedCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实 PostgreSQL 验证 Canvas Function 在共享 durable work 上的多节点 claim、lease 恢复、fencing 与 NOTIFY。 */
class CanvasFunctionWorkStoreIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant T0 = Instant.parse("2026-02-03T04:05:06.123Z");
  private static final Duration LEASE = Duration.ofSeconds(30);

  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionWorkStore workStore;
  @Autowired private CanvasFunctionRunJdbcStore runJdbcStore;
  @Autowired private CanvasFunctionRunStateCodecPort stateCodec;

  /** 清理不可运行 Work 后必须在同一次调用继续领取有效 Run。 */
  @ParameterizedTest
  @ValueSource(strings = {"SUCCEEDED", "UNKNOWN", "MISSING"})
  void skipsInvalidWorkAndContinues(String status) {
    UUID canvasId = addDocument();
    UUID invalid = addNode(canvasId, true).id();
    UUID valid = addNode(canvasId, true).id();
    if (!status.equals("MISSING")) {
      insertRunRow(invalid, UUID.randomUUID(), status);
    }
    DurableWorkQueue queue =
        new DurableWorkQueue(
            JdbcClient.create(jdbc), transactions.getTransactionManager(), "CANVAS_RUN");
    queue.request(invalid, Duration.ZERO);
    jdbc.update(
        "update durable_work set available_at = statement_timestamp() - interval '1 day' where target_id = ?",
        invalid);
    runs.insertReady(ready(valid, UUID.randomUUID(), T0));

    assertEquals(valid, workStore.claimNext(LEASE, "owner").orElseThrow().nodeId());
    assertEquals(0, workRows(invalid));
    assertEquals(1, runs.findByNodeId(valid).orElseThrow().attempt());
  }

  /** 真实行锁覆盖 attempt 写入，且 Run 锁必须先于 Work ownership 锁。 */
  @Test
  void holdsRunAndWorkLocksThroughAttemptWrite() {
    UUID nodeId = addNode(addDocument(), true).id();
    runs.insertReady(ready(nodeId, UUID.randomUUID(), T0));
    DurableWorkQueue queue =
        spy(
            new DurableWorkQueue(
                JdbcClient.create(jdbc), transactions.getTransactionManager(), "CANVAS_RUN"));
    CanvasFunctionRunJdbcStore observedRuns =
        spy(AopTestUtils.<CanvasFunctionRunJdbcStore>getUltimateTargetObject(runJdbcStore));
    doAnswer(
            invocation -> {
              for (String table : List.of("canvas_function_run", "durable_work")) {
                try (Connection connection = newConnection();
                    var statement =
                        connection.prepareStatement(
                            "select * from "
                                + table
                                + " where "
                                + (table.equals("durable_work") ? "target_id" : "node_id")
                                + " = ? for update nowait")) {
                  statement.setObject(1, nodeId);
                  SQLException error = assertThrows(SQLException.class, statement::executeQuery);
                  assertEquals("55P03", error.getSQLState());
                }
              }
              return invocation.callRealMethod();
            })
        .when(observedRuns)
        .startAttempt(any(), any());
    CanvasFunctionWorkStore observed =
        new CanvasFunctionWorkStore(
            queue,
            observedRuns,
            stateCodec,
            transactions.getTransactionManager(),
            Clock.systemUTC());

    assertEquals(1, observed.claimNext(LEASE, "owner").orElseThrow().run().attempt());
    var order = inOrder(observedRuns, queue);
    order.verify(observedRuns).findByNodeIdForUpdate(nodeId);
    order.verify(queue).lockOwned(any());
    order.verify(observedRuns).startAttempt(any(), any());
  }

  /** 暂停在队列领取与 Run 事务之间，新持有者接管后旧领取不得启动 attempt。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void takeoverBeforeStartFencesOldClaim(boolean takeover) throws Exception {
    UUID nodeId = addNode(addDocument(), true).id();
    runs.insertReady(ready(nodeId, UUID.randomUUID(), T0));
    DurableWorkQueue queue =
        spy(
            new DurableWorkQueue(
                JdbcClient.create(jdbc), transactions.getTransactionManager(), "CANVAS_RUN"));
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              claimed.countDown();
              assertTrue(resume.await(10, TimeUnit.SECONDS));
              return result;
            })
        .when(queue)
        .claimNext("owner-a", LEASE);
    CanvasFunctionWorkStore delayed =
        new CanvasFunctionWorkStore(
            queue,
            runJdbcStore,
            stateCodec,
            transactions.getTransactionManager(),
            Clock.systemUTC());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old = executor.submit(() -> delayed.claimNext(LEASE, "owner-a"));
      try {
        assertTrue(claimed.await(10, TimeUnit.SECONDS));
        jdbc.update(
            "update durable_work set lease_until = statement_timestamp() - interval '1 second' where target_id = ?",
            nodeId);
        if (takeover) {
          assertEquals(1, workStore.claimNext(LEASE, "owner-b").orElseThrow().run().attempt());
        }
      } finally {
        resume.countDown();
      }
      assertTrue(old.get(10, TimeUnit.SECONDS).isEmpty());
      assertEquals(takeover ? 1 : 0, runs.findByNodeId(nodeId).orElseThrow().attempt());
      verify(queue).claimNext("owner-a", LEASE);
    }
  }

  /** V1 CHECK 必须拒绝负 attempt；调度与租约不在 Run 行上。 */
  @Test
  void schemaRejectsNegativeAttempt() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                """
                insert into canvas_function_run (
                    node_id, request_id, status, attempt, state_json, updated_at, created_at)
                values (?, ?, 'READY', -1, cast(? as jsonb), ?, ?)
                """,
                node.id(),
                UUID.randomUUID(),
                state("QUEUED"),
                T0,
                T0));
  }

  /** 两个实例并发 claim 必须由 SKIP LOCKED 分到不同 READY 行，不能重复拥有同一 run。 */
  @Test
  void concurrentClaimUsesSkipLockedAndClaimsEachRunOnce() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord first = addNode(canvasId, true);
    NodeRecord second = addNode(canvasId, true);
    runs.insertReady(ready(first.id(), UUID.randomUUID(), T0));
    runs.insertReady(ready(second.id(), UUID.randomUUID(), T0));

    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Callable<ClaimedRun>> tasks =
          List.of(
              () -> workStore.claimNext(LEASE, "owner-a").orElseThrow(),
              () -> workStore.claimNext(LEASE, "owner-b").orElseThrow());
      List<Future<ClaimedRun>> futures = executor.invokeAll(tasks);
      Set<UUID> claimed =
          new HashSet<>(List.of(futures.get(0).get().nodeId(), futures.get(1).get().nodeId()));
      assertEquals(Set.of(first.id(), second.id()), claimed);
    }
  }

  /** 过期 RUNNING 必须被新 token 恢复，attempt 增加且旧 token 的 checkpoint/terminal 都被 fence。 */
  @Test
  void expiredLeaseIsRecoveredAndOldTokenIsFenced() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    runs.insertReady(ready(node.id(), requestId, T0));

    ClaimedRun first = workStore.claimNext(LEASE, "owner-old").orElseThrow();
    assertEquals(1, first.run().attempt());
    awaitLeaseExpiry();
    ClaimedRun recovered = workStore.claimNext(LEASE, "owner-new").orElseThrow();
    assertEquals(2, recovered.run().attempt());
    assertNotEquals(first.leaseToken(), recovered.leaseToken());

    assertFalse(
        runs.checkpoint(
            node.id(), requestId, first.leaseToken(), state("LATE"), "LATE", T0.plus(LEASE)));
    assertFalse(
        runs.transitionTerminal(
            terminal(recovered.run(), CanvasFunctionRunStatus.FAILED, "FAILED"),
            first.leaseToken()));
    assertTrue(
        runs.transitionTerminal(
            terminal(recovered.run(), CanvasFunctionRunStatus.SUCCEEDED, "SUCCEEDED"),
            recovered.leaseToken()));
    CanvasFunctionRun stored = runs.findByNodeId(node.id()).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.SUCCEEDED, stored.status());
    assertEquals(0, workRows(node.id()), "a finished Run leaves no durable work behind");
  }

  /** heartbeat 只能延长仍有效且匹配的 lease；旧 token 或已过期 lease 不能复活 ownership。 */
  @Test
  void renewRequiresTheActiveMatchingLease() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun claim = workStore.claimNext(LEASE, "owner").orElseThrow();

    assertTrue(workStore.renew(claim, LEASE));
    jdbc.update(
        "update durable_work set lease_until = statement_timestamp() - interval '1 second'"
            + " where target_type = 'CANVAS_RUN' and target_id = ?",
        node.id());
    assertFalse(workStore.renew(claim, LEASE), "an expired lease cannot be revived");
  }

  /** rejection 归还必须只由当前 owner 把 RUNNING 延迟重排为 READY，旧 token 再归还为 no-op。 */
  @Test
  void rescheduleReturnsOnlyTheOwnedClaim() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun claim = workStore.claimNext(LEASE, "owner").orElseThrow();
    assertTrue(runs.holdsLease(node.id(), claim.leaseToken()));

    assertTrue(workStore.reschedule(claim, Duration.ofMinutes(2)));
    CanvasFunctionRun returned = runs.findByNodeId(node.id()).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.READY, returned.status());
    assertTrue(workStore.claimNext(LEASE, "too-early").isEmpty(), "the retry is deferred");
    assertFalse(workStore.reschedule(claim, Duration.ofSeconds(2)));
  }

  /** work store 在 SQL 前拒绝空 token、非正或非整毫秒 duration。 */
  @Test
  void rejectsInvalidClaimArgumentsBeforeDatabaseAccess() {
    assertThrows(
        IllegalArgumentException.class, () -> workStore.claimNext(Duration.ofSeconds(1), ""));
    assertThrows(IllegalArgumentException.class, () -> workStore.claimNext(Duration.ZERO, "owner"));
  }

  /** Scheduled heartbeat 必须通过真实 PostgreSQL renew 延长阻塞 adapter 的当前 lease。 */
  @Test
  @SuppressWarnings("unchecked")
  void workerHeartbeatRenewsTheRealPostgresqlLease() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    UUID requestId = UUID.randomUUID();
    runs.insertReady(ready(node.id(), requestId, now));
    ClaimedRun claim =
        workStore.claimNext(Duration.ofSeconds(5), "real-heartbeat-owner").orElseThrow();
    Instant initialLeaseUntil = claim.work().leaseUntil();
    UUID targetResourceId = UUID.randomUUID();
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    when(frozen.nodeId()).thenReturn(node.id());
    when(frozen.canvasId()).thenReturn(canvasId);
    when(frozen.requestId()).thenReturn(requestId);
    when(frozen.outputResourceIds()).thenReturn(List.of(targetResourceId));
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            "test.function",
            "Test Function",
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    when(frozen.submitted()).thenReturn(true);
    when(frozen.submitState()).thenReturn(CanvasFunctionSubmitState.SUBMITTED);
    when(adapter.functions()).thenReturn(List.of(definition));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.unavailableReason()).thenReturn(null);
    CountDownLatch renewalCompleted = new CountDownLatch(1);
    AtomicBoolean renewalObserved = new AtomicBoolean();
    CanvasFunctionWorkStore workStoreTarget = AopTestUtils.getUltimateTargetObject(workStore);
    CanvasFunctionWorkStore observedWorkStore = spy(workStoreTarget);
    doAnswer(
            invocation -> {
              boolean renewed = (Boolean) invocation.callRealMethod();
              if (renewed) {
                renewalCompleted.countDown();
              }
              return renewed;
            })
        .when(observedWorkStore)
        .renew(any(), any());
    when(adapter.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              renewalObserved.set(renewalCompleted.await(5, TimeUnit.SECONDS));
              return List.of(targetResourceId);
            });
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(frozen.binding()).thenReturn(new CanvasFunctionBinding(definition));
    when(stateCodec.decode(claim.run().stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions runTransactions = mock(CanvasFunctionRunTransactions.class);
    ObjectProvider<CanvasResourceMaterializer> materializers = mock(ObjectProvider.class);
    when(materializers.getIfAvailable()).thenReturn(mock(CanvasResourceMaterializer.class));
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.setLeaseDurationMillis(5_000);
    properties.setHeartbeatIntervalMillis(50);
    ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    CanvasFunctionWorker worker =
        new CanvasFunctionWorker(
            runs,
            new FixedCanvasFunctionCatalogSource(catalog),
            stateCodec,
            runTransactions,
            mock(CanvasFunctionBlobAccess.class),
            materializers,
            observedWorkStore,
            properties,
            heartbeat);
    try {
      worker.run(claim);
    } finally {
      heartbeat.shutdownNow();
    }

    assertTrue(renewalObserved.get(), "adapter must observe a successful real PostgreSQL renewal");
    assertTrue(
        leaseUntil(node.id()).isAfter(initialLeaseUntil),
        "real PostgreSQL renewal must extend the lease");
    verify(runTransactions).completeSuccess(frozen, claim.leaseToken(), List.of(targetResourceId));
  }

  /** cancel READY/RUNNING 都必须原子删除调度行，使已领取 worker 立即失去 ownership。 */
  @Test
  void cancelActiveClearsLeaseAndInvalidatesWorker() {
    UUID canvasId = addDocument();
    NodeRecord readyNode = addNode(canvasId, true);
    NodeRecord runningNode = addNode(canvasId, true);
    CanvasFunctionRun ready = ready(readyNode.id(), UUID.randomUUID(), T0);
    CanvasFunctionRun runningReady = ready(runningNode.id(), UUID.randomUUID(), T0);
    runs.insertReady(ready);
    runs.insertReady(runningReady);
    ClaimedRun claimed = workStore.claimNext(LEASE, "owner").orElseThrow();
    CanvasFunctionRun unclaimed = claimed.nodeId().equals(ready.nodeId()) ? runningReady : ready;

    assertTrue(
        runs.cancelActive(terminal(unclaimed, CanvasFunctionRunStatus.CANCELLED, "CANCELLED")));
    assertTrue(
        runs.cancelActive(terminal(claimed.run(), CanvasFunctionRunStatus.CANCELLED, "CANCELLED")));
    assertFalse(runs.holdsLease(claimed.nodeId(), claimed.leaseToken()));
    assertEquals(0, workRows(readyNode.id()) + workRows(runningNode.id()));
  }

  /** READY insert 提交后在共享 durable work 通道发送 CANVAS_RUN 提示。 */
  @Test
  void readyCommitSendsAnImmediateEmptyNotification() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));

      PGNotification[] notifications = pollNotifications(listener, 5_000);
      assertEquals(1, notifications.length);
      assertEquals(DurableWorkQueue.CHANNEL, notifications[0].getName());
      assertEquals("CANVAS_RUN", notifications[0].getParameter());
    }
  }

  /** READY 写入回滚时 PostgreSQL 不得投递 NOTIFY，行、pin/version 等事务事实也不能部分可见。 */
  @Test
  void rolledBackReadyInsertDoesNotNotify() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              transactions.executeWithoutResult(
                  ignored -> {
                    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
                    throw new IllegalStateException("rollback");
                  }));

      assertNoNotification(listener);
      assertTrue(runs.findByNodeId(node.id()).isEmpty());
    }
  }

  /** claim 转 RUNNING 与终态收敛都不是可立即领取的 READY，不得产生通知。 */
  @Test
  void runningAndTerminalWritesStaySilent() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun claim = workStore.claimNext(LEASE, "owner").orElseThrow();

    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      assertTrue(
          runs.transitionTerminal(
              terminal(claim.run(), CanvasFunctionRunStatus.SUCCEEDED, "SUCCEEDED"),
              claim.leaseToken()));

      assertNoNotification(listener);
    }
  }

  /** 归还只在重排后的最终事实可立即领取时唤醒；延迟到未来 available_at 时保持静默。 */
  @Test
  void rescheduleNotifiesOnlyWhenImmediatelyClaimable() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord immediateNode = addNode(canvasId, true);
    NodeRecord futureNode = addNode(canvasId, true);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    runs.insertReady(ready(immediateNode.id(), UUID.randomUUID(), now));
    runs.insertReady(ready(futureNode.id(), UUID.randomUUID(), now));

    ClaimedRun immediate = workStore.claimNext(LEASE, "owner-immediate").orElseThrow();
    ClaimedRun future = workStore.claimNext(LEASE, "owner-future").orElseThrow();

    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      // 立即重排 → 立即领取提示
      assertTrue(workStore.reschedule(immediate, Duration.ZERO));
      PGNotification[] notifications = pollNotifications(listener, 5_000);
      assertEquals(1, notifications.length);
      assertEquals("CANVAS_RUN", notifications[0].getParameter());

      // 推迟到未来 → 静默
      assertTrue(workStore.reschedule(future, Duration.ofHours(1)));
      assertNoNotification(listener);
    }
  }

  /** 围栏失效的归还（旧 token）不写入任何行，也不得通知。 */
  @Test
  void fencedRescheduleStaysSilent() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun stale = workStore.claimNext(LEASE, "owner-old").orElseThrow();
    awaitLeaseExpiry();
    workStore.claimNext(LEASE, "owner-new").orElseThrow();

    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      assertFalse(workStore.reschedule(stale, Duration.ofSeconds(1)));

      assertNoNotification(listener);
    }
  }

  /** 终态替换为 READY 也必须在成功写入后立即唤醒。 */
  @Test
  void replaceTerminalWithReadyNotifiesImmediately() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    insertRunRow(node.id(), UUID.randomUUID(), "CANCELLED");

    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      assertTrue(runs.replaceTerminalWithReady(ready(node.id(), UUID.randomUUID(), T0)));
      PGNotification[] notifications = pollNotifications(listener, 5_000);
      assertEquals(1, notifications.length);
      assertEquals("CANVAS_RUN", notifications[0].getParameter());
    }
  }

  /** UNKNOWN 人工解除为 READY 必须在成功写入后立即唤醒。 */
  @Test
  void resumeUnknownNotifiesImmediately() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    insertRunRow(node.id(), requestId, "UNKNOWN");

    try (Connection listener = listenOn(DurableWorkQueue.CHANNEL)) {
      assertTrue(runs.resumeUnknown(ready(node.id(), requestId, T0)));
      PGNotification[] notifications = pollNotifications(listener, 5_000);
      assertEquals(1, notifications.length);
      assertEquals("CANVAS_RUN", notifications[0].getParameter());
    }
  }

  /** 以数据库时间确定性地使本测试的租约过期。 */
  private void awaitLeaseExpiry() {
    jdbc.update(
        "update durable_work set lease_until = statement_timestamp() - interval '1 second' where target_type = 'CANVAS_RUN'");
  }

  private int workRows(UUID nodeId) {
    return jdbc.queryForObject(
        "select count(*) from durable_work where target_type = 'CANVAS_RUN' and target_id = ?",
        Integer.class,
        nodeId);
  }

  private Instant leaseUntil(UUID nodeId) {
    return jdbc.queryForObject(
            "select lease_until from durable_work where target_type = 'CANVAS_RUN'"
                + " and target_id = ?",
            Timestamp.class,
            nodeId)
        .toInstant();
  }

  /** 直接写入非 READY 行，准备终态替换与 UNKNOWN 解除的既有事实。 */
  private void insertRunRow(UUID nodeId, UUID requestId, String status) {
    jdbc.update(
        "insert into canvas_function_run (node_id, request_id, status, attempt, state_json, error,"
            + " updated_at, created_at) values (?, ?, ?, 0, cast(? as jsonb), ?, current_timestamp,"
            + " current_timestamp)",
        nodeId,
        requestId,
        status,
        minimalRunState(status),
        "UNKNOWN".equals(status) ? "unknown" : null);
  }

  private static CanvasFunctionRun ready(UUID nodeId, UUID requestId, Instant now) {
    return new CanvasFunctionRun(
        nodeId,
        requestId,
        CanvasFunctionRunStatus.READY,
        0,
        "QUEUED",
        state("QUEUED"),
        null,
        now,
        now);
  }

  private static CanvasFunctionRun terminal(
      CanvasFunctionRun current, CanvasFunctionRunStatus status, String stage) {
    return new CanvasFunctionRun(
        current.nodeId(),
        current.requestId(),
        status,
        current.attempt(),
        stage,
        state(stage),
        status == CanvasFunctionRunStatus.FAILED ? "failed" : null,
        current.updatedAt().plusSeconds(1),
        current.createdAt());
  }

  private static String state(String stage) {
    return "{\"stage\":\"" + stage + "\"}";
  }
}
