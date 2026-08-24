package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRunTransactions;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionWorker;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 真实 PostgreSQL 验证 Canvas Function queue 的多节点 claim、lease 恢复、fencing 与 NOTIFY。 */
class CanvasFunctionWorkStoreIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant T0 = Instant.parse("2026-02-03T04:05:06.123Z");
  private static final Duration LEASE = Duration.ofSeconds(30);

  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionWorkStore workStore;

  /** V1 CHECK 必须拒绝负 attempt、缺 availableAt 的 READY 与携带 lease 的 terminal。 */
  @Test
  void schemaRejectsInvalidWorkStateCombinations() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    String sql =
        """
        insert into canvas_function_run (
            node_id, request_id, status, attempt, available_at, lease_token, lease_until,
            state_json, updated_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
        """;
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                sql,
                node.id(),
                UUID.randomUUID(),
                "READY",
                -1,
                T0,
                null,
                null,
                state("QUEUED"),
                T0,
                T0));
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                sql,
                node.id(),
                UUID.randomUUID(),
                "READY",
                0,
                null,
                null,
                null,
                state("QUEUED"),
                T0,
                T0));
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.update(
                sql,
                node.id(),
                UUID.randomUUID(),
                "SUCCEEDED",
                1,
                null,
                "lease",
                T0.plusSeconds(30),
                state("SUCCEEDED"),
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
              () -> workStore.claimNext(T0, LEASE, "owner-a").orElseThrow(),
              () -> workStore.claimNext(T0, LEASE, "owner-b").orElseThrow());
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

    ClaimedRun first = workStore.claimNext(T0, LEASE, "owner-old").orElseThrow();
    assertEquals(1, first.run().attempt());
    ClaimedRun recovered = workStore.claimNext(T0.plus(LEASE), LEASE, "owner-new").orElseThrow();
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
    assertNull(stored.leaseToken());
    assertNull(stored.leaseUntil());
  }

  /** heartbeat 只能延长仍有效且匹配的 lease；旧 token 或已过期 lease 不能复活 ownership。 */
  @Test
  void renewRequiresTheActiveMatchingLease() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun claim = workStore.claimNext(T0, LEASE, "owner").orElseThrow();

    assertTrue(workStore.renew(claim, T0.plusSeconds(10), LEASE));
    assertFalse(workStore.renew(claim, T0.plusSeconds(41), LEASE));
  }

  /** rejection 归还必须只由当前 owner 把 RUNNING 延迟重排为 READY，旧 token 再归还为 no-op。 */
  @Test
  void rescheduleReturnsOnlyTheOwnedClaim() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
    ClaimedRun claim = workStore.claimNext(T0, LEASE, "owner").orElseThrow();
    assertTrue(workStore.isOwned(claim, T0.plusSeconds(1)));

    assertTrue(workStore.reschedule(claim, T0.plusSeconds(1), Duration.ofSeconds(2)));
    CanvasFunctionRun returned = runs.findByNodeId(node.id()).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.READY, returned.status());
    assertEquals(T0.plusSeconds(3), returned.availableAt());
    assertFalse(workStore.reschedule(claim, T0.plusSeconds(1), Duration.ofSeconds(2)));
  }

  /** work store 在 SQL 前拒绝空 token、非正或非整毫秒 duration。 */
  @Test
  void rejectsInvalidClaimArgumentsBeforeDatabaseAccess() {
    assertThrows(
        IllegalArgumentException.class, () -> workStore.claimNext(T0, Duration.ofSeconds(1), ""));
    assertThrows(
        IllegalArgumentException.class, () -> workStore.claimNext(T0, Duration.ZERO, "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> workStore.claimNext(T0, Duration.ofNanos(1_500_000), "owner"));
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
        workStore.claimNext(now, Duration.ofSeconds(5), "real-heartbeat-owner").orElseThrow();
    Instant initialLeaseUntil = claim.run().leaseUntil();
    UUID targetResourceId = UUID.randomUUID();
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    when(frozen.nodeId()).thenReturn(node.id());
    when(frozen.requestId()).thenReturn(requestId);
    when(frozen.targetResourceId()).thenReturn(targetResourceId);
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(adapter.enabled()).thenReturn(true);
    when(adapter.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
              while (!runs.findByNodeId(node.id())
                  .orElseThrow()
                  .leaseUntil()
                  .isAfter(initialLeaseUntil)) {
                if (System.nanoTime() >= deadline) {
                  throw new AssertionError("heartbeat did not renew the PostgreSQL lease");
                }
                Thread.onSpinWait();
              }
              return List.of(targetResourceId);
            });
    CanvasFunctionCatalog catalog = mock(CanvasFunctionCatalog.class);
    CanvasFunctionModel model = mock(CanvasFunctionModel.class);
    when(catalog.require("model"))
        .thenReturn(new CanvasFunctionCatalog.RegisteredModel(model, adapter));
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.modelKey(claim.run().stateJson())).thenReturn("model");
    when(stateCodec.decode(claim.run().stateJson(), model)).thenReturn(frozen);
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
            catalog,
            stateCodec,
            runTransactions,
            mock(CanvasFunctionBlobAccess.class),
            materializers,
            workStore,
            properties,
            Clock.systemUTC(),
            heartbeat);
    try {
      worker.run(claim);
    } finally {
      heartbeat.shutdownNow();
    }

    assertTrue(runs.findByNodeId(node.id()).orElseThrow().leaseUntil().isAfter(initialLeaseUntil));
    verify(runTransactions).completeSuccess(frozen, claim.leaseToken(), List.of(targetResourceId));
  }

  /** cancel READY/RUNNING 都必须原子清除 claim 字段，使已领取 worker 立即失去 ownership。 */
  @Test
  void cancelActiveClearsLeaseAndInvalidatesWorker() {
    UUID canvasId = addDocument();
    NodeRecord readyNode = addNode(canvasId, true);
    NodeRecord runningNode = addNode(canvasId, true);
    CanvasFunctionRun ready = ready(readyNode.id(), UUID.randomUUID(), T0);
    CanvasFunctionRun runningReady = ready(runningNode.id(), UUID.randomUUID(), T0);
    runs.insertReady(ready);
    runs.insertReady(runningReady);
    ClaimedRun claimed = workStore.claimNext(T0, LEASE, "owner").orElseThrow();
    CanvasFunctionRun unclaimed = claimed.nodeId().equals(ready.nodeId()) ? runningReady : ready;

    assertTrue(
        runs.cancelActive(terminal(unclaimed, CanvasFunctionRunStatus.CANCELLED, "CANCELLED")));
    assertTrue(
        runs.cancelActive(terminal(claimed.run(), CanvasFunctionRunStatus.CANCELLED, "CANCELLED")));
    assertFalse(workStore.isOwned(claimed, T0.plusSeconds(1)));
  }

  /** READY insert 提交后 trigger 必须立即发送空 payload；claim 更新为 RUNNING 不产生伪通知。 */
  @Test
  void readyCommitSendsAnImmediateEmptyNotification() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      listener.setAutoCommit(true);
      statement.execute("listen canvas_function_work");
      runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));

      PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(5_000);
      assertEquals(1, notifications.length);
      assertEquals("canvas_function_work", notifications[0].getName());
      assertEquals("", notifications[0].getParameter());
    }
  }

  /** READY 写入回滚时 PostgreSQL 不得投递 NOTIFY，行、pin/version 等事务事实也不能部分可见。 */
  @Test
  void rolledBackReadyInsertDoesNotNotify() throws Exception {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      listener.setAutoCommit(true);
      statement.execute("listen canvas_function_work");
      assertThrows(
          IllegalStateException.class,
          () ->
              transactions.executeWithoutResult(
                  ignored -> {
                    runs.insertReady(ready(node.id(), UUID.randomUUID(), T0));
                    throw new IllegalStateException("rollback");
                  }));

      PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(200);
      assertTrue(notifications == null || notifications.length == 0);
      assertTrue(runs.findByNodeId(node.id()).isEmpty());
    }
  }

  private static CanvasFunctionRun ready(UUID nodeId, UUID requestId, Instant now) {
    return new CanvasFunctionRun(
        nodeId,
        requestId,
        CanvasFunctionRunStatus.READY,
        0,
        now,
        null,
        null,
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
        null,
        null,
        null,
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
