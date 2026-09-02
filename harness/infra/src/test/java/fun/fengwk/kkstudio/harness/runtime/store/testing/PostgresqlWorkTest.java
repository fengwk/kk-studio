package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class PostgresqlWorkTest extends HarnessStoreWorkContract {

  @Override
  HarnessStore createStore() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    seedEnvironment(jdbc, EnvironmentId.parse("11111111-1111-1111-1111-111111111111"));
    seedEnvironment(jdbc, EnvironmentId.parse("22222222-2222-2222-2222-222222222222"));
    seedEnvironment(jdbc, EnvironmentId.parse("33333333-3333-3333-3333-333333333333"));
    seedEnvironment(jdbc, EnvironmentId.parse("44444444-4444-4444-4444-444444444444"));
    return store;
  }

  private static Instant now() {
    return Instant.ofEpochMilli(System.currentTimeMillis());
  }

  @Override
  protected ClaimedWork claimNext(WorkTargetType type, Instant now) {
    return store
        .transaction(tx -> tx.claimNextWork(type, now, "lease-token", now.plusSeconds(60)))
        .orElseThrow();
  }

  @Test
  @Override
  void claimNextWorkRequiresAvailableTimeAndFreeOrExpiredLease() {
    HarnessStore store = createStore();
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());

    Instant current = now();
    // 1. 未来可用时间
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, current.plusMillis(500));
        });

    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, current, "token-1", current.plusSeconds(4)))
            .isEmpty());

    // 直接推进持久化时间边界，避免 JVM clock、database clock 与短 sleep 之间的竞态。
    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set available_at = statement_timestamp() - interval '1 second'
            where target_type = ? and target_id = ?
            """,
            target.type().name(),
            target.id()));

    Instant claimTime = now();
    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, claimTime, "token-1", claimTime.plusSeconds(60)))
            .orElseThrow();

    // 活跃 lease 阻止其他 claim
    Instant blockedTime = now();
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, blockedTime, "token-2", blockedTime.plusSeconds(60)))
            .isEmpty());

    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set lease_until = statement_timestamp() - interval '1 second'
            where target_type = ? and target_id = ?
            """,
            target.type().name(),
            target.id()));

    Instant reclaimTime = now();
    ClaimedWork reclaimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, reclaimTime, "token-3", reclaimTime.plusSeconds(60)))
            .orElseThrow();
    assertNotEquals(claimed.leaseToken(), reclaimed.leaseToken());
    assertEquals(1L, reclaimed.claimedWakeVersion());
  }

  @Test
  @Override
  void claimNextWorkIsOrderedByAvailableAtThenTargetId() {
    HarnessStore store = createStore();
    Baseline baseline = seedThreadBaseline(store);
    UUID thread2 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
              return id;
            });
    UUID thread3 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
              return id;
            });

    Instant current = now();
    Instant due1 = current.minusSeconds(10);
    Instant due2 = current.minusSeconds(5);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockThread(thread2).orElseThrow();
          tx.lockThread(thread3).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), due1);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread2), due2);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread3), due1);
        });

    ClaimedWork first = claimNext(WorkTargetType.THREAD, current);
    assertEquals(baseline.threadId(), first.target().id());

    ClaimedWork second = claimNext(WorkTargetType.THREAD, current);
    assertEquals(thread3, second.target().id());

    ClaimedWork third = claimNext(WorkTargetType.THREAD, current);
    assertEquals(thread2, third.target().id());

    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, current, "token-4", now().plusSeconds(60)))
            .isEmpty());
  }

  @Test
  @Override
  void claimNextWorkReturnsClaimedWorkWithEnvironmentAffinity() {
    HarnessStore store = createStore();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    UUID node = UUID.randomUUID();
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    seedEnvironmentConnection(jdbc, env, "daemon-1", node, "READY");

    SeededTool seeded = seedTool(store);
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    Instant current = now();
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          tx.requestWork(toolTarget, current.minusSeconds(10), env);
        });

    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL, current, "token", current.plusSeconds(30), node))
            .orElseThrow();
    assertEquals(toolTarget, claimed.target());
    assertEquals(env, claimed.requiredEnvironmentId());
  }

  @Test
  void claimNextWorkEnforcesPostgresqlRouteAffinity() {
    HarnessStore store = createStore();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());

    UUID node1 = UUID.randomUUID();
    UUID node2 = UUID.randomUUID();
    UUID node3 = UUID.randomUUID();

    // 播种 environment_connection:
    // node1: env-1 (READY, fresh lease)
    // node2: env-2 (READY, fresh lease)
    // node1: env-stale (READY, expired lease)
    // node1: env-connecting (CONNECTING, fresh lease)
    seedEnvironmentConnection(
        jdbc,
        EnvironmentId.parse("11111111-1111-1111-1111-111111111111"),
        "daemon-1",
        node1,
        "READY");
    seedEnvironmentConnection(
        jdbc,
        EnvironmentId.parse("22222222-2222-2222-2222-222222222222"),
        "daemon-2",
        node2,
        "READY");
    seedEnvironmentConnection(
        jdbc,
        EnvironmentId.parse("33333333-3333-3333-3333-333333333333"),
        "daemon-stale",
        node1,
        "READY");
    jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '20 seconds', lease_until = statement_timestamp() - interval '10 seconds' where environment_id = ?",
        UUID.fromString("33333333-3333-3333-3333-333333333333"));
    seedEnvironmentConnection(
        jdbc,
        EnvironmentId.parse("44444444-4444-4444-4444-444444444444"),
        "daemon-conn",
        node1,
        "CONNECTING");

    // 播种 5 个 Tool Work
    SeededTool toolSeed1 = seedTool(store);
    SeededTool toolSeed2 = seedTool(store);
    SeededTool toolSeed3 = seedTool(store);
    SeededTool toolSeed4 = seedTool(store);
    SeededTool toolSeed5 = seedTool(store);

    Instant current = now();
    Instant due = current.minusSeconds(10);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed1.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed1.toolId()),
              due,
              EnvironmentId.parse("11111111-1111-1111-1111-111111111111"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed2.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed2.toolId()),
              due,
              EnvironmentId.parse("22222222-2222-2222-2222-222222222222"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed3.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed3.toolId()),
              due,
              EnvironmentId.parse("33333333-3333-3333-3333-333333333333"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed4.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed4.toolId()),
              due,
              EnvironmentId.parse("44444444-4444-4444-4444-444444444444"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed5.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolSeed5.toolId()), due, null);
        });

    // 1. Node3 (无 route): 只能 claim 到 tool5 (null affinity)
    Optional<ClaimedWork> claimNode3 =
        store.transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.TOOL, current, "token-node3", current.plusSeconds(30), node3));
    assertTrue(claimNode3.isPresent());
    assertEquals(toolSeed5.toolId(), claimNode3.get().target().id());

    // 2. Node3 再次 claim: 无法 claim 任何剩余 tool work
    Optional<ClaimedWork> emptyForNode3 =
        store.transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.TOOL, current, "token-node3-2", current.plusSeconds(30), node3));
    assertTrue(emptyForNode3.isEmpty());

    // 3. Node2 (持有 env-2 READY 路由): 只能 claim 到 tool2 (env-2)
    Optional<ClaimedWork> claimNode2 =
        store.transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.TOOL, current, "token-node2", current.plusSeconds(30), node2));
    assertTrue(claimNode2.isPresent());
    assertEquals(toolSeed2.toolId(), claimNode2.get().target().id());

    // 4. Node1 (持有 env-1 READY 路由): 只能 claim 到 tool1 (env-1)
    Optional<ClaimedWork> claimNode1 =
        store.transaction(
            tx ->
                tx.claimNextWork(
                    WorkTargetType.TOOL, current, "token-node1", current.plusSeconds(30), node1));
    assertTrue(claimNode1.isPresent());
    assertEquals(toolSeed1.toolId(), claimNode1.get().target().id());

    // 5. 剩余 tool3 (stale lease) 和 tool4 (CONNECTING): 任何 node 都无法 claim
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL,
                        current,
                        "token-node1-2",
                        current.plusSeconds(30),
                        node1))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL,
                        current,
                        "token-node2-2",
                        current.plusSeconds(30),
                        node2))
            .isEmpty());
  }

  private static void seedEnvironment(JdbcTemplate jdbc, EnvironmentId env) {
    jdbc.update(
        """
        insert into environment (id, name, registration_token, version)
        values (?, ?, ?, 0)
        on conflict (id) do nothing
        """,
        env.value(),
        "env-" + env.value().toString().substring(0, 8),
        "token-" + env.value());
  }

  /** 按 V1 environment_connection 表形状播种一条 daemon 连接路由，供 claim affinity 断言使用。 */
  private static void seedEnvironmentConnection(
      JdbcTemplate jdbc, EnvironmentId env, String daemonId, UUID node, String status) {
    seedEnvironment(jdbc, env);
    jdbc.update(
        """
        insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until)
        values (?, ?, gen_random_uuid(), ?, ?::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')
        on conflict (environment_id) do update
        set owner_node_id = excluded.owner_node_id,
            status = excluded.status,
            runtime_info = excluded.runtime_info,
            last_seen_at = excluded.last_seen_at,
            lease_until = excluded.lease_until
        """,
        env.value(),
        node,
        status,
        "READY".equals(status) ? "{}" : null);
  }
}
