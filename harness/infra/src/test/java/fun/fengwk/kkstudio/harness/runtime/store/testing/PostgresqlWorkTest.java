package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
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
    return PostgresqlHarnessStoreFixture.resetAndCreate();
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

    // 等待 available_at 到达
    sleep(600);

    ClaimedWork claimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, current, "token-1", now().plusMillis(500)))
            .orElseThrow();

    // 活跃 lease 阻止其他 claim
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, current, "token-2", now().plusSeconds(4)))
            .isEmpty());

    // 等待 lease 过期
    sleep(600);

    ClaimedWork reclaimed =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, current, "token-3", now().plusSeconds(4)))
            .orElseThrow();
    assertNotEquals(claimed.leaseToken(), reclaimed.leaseToken());
    assertEquals(1L, reclaimed.claimedWakeVersion());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", e);
    }
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
    EnvironmentName env = new EnvironmentName("env-1");
    jdbc.update(
        "insert into live_environment (environment_name, daemon_id, owner_node_id, route_token, status, capabilities, last_seen_at, lease_until) values (?, 'daemon-1', ?, gen_random_uuid(), 'READY', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')",
        env.value(),
        node);

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
    assertEquals(env, claimed.requiredEnvironmentName());
  }

  @Test
  void claimNextWorkEnforcesPostgresqlRouteAffinity() {
    HarnessStore store = createStore();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());

    UUID node1 = UUID.randomUUID();
    UUID node2 = UUID.randomUUID();
    UUID node3 = UUID.randomUUID();

    // 播种 live_environment:
    // node1: env-1 (READY, fresh lease)
    // node2: env-2 (READY, fresh lease)
    // node1: env-stale (READY, expired lease)
    // node1: env-connecting (CONNECTING, fresh lease)
    jdbc.update(
        """
        insert into live_environment (environment_name, daemon_id, owner_node_id, route_token, status, capabilities, last_seen_at, lease_until)
        values
            ('env-1', 'daemon-1', ?, gen_random_uuid(), 'READY', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds'),
            ('env-2', 'daemon-2', ?, gen_random_uuid(), 'READY', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds'),
            ('env-stale', 'daemon-stale', ?, gen_random_uuid(), 'READY', '{}'::jsonb, statement_timestamp() - interval '20 seconds', statement_timestamp() - interval '10 seconds'),
            ('env-connecting', 'daemon-conn', ?, gen_random_uuid(), 'CONNECTING', null, statement_timestamp(), statement_timestamp() + interval '60 seconds')
        """,
        node1,
        node2,
        node1,
        node1);

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
              new EnvironmentName("env-1"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed2.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed2.toolId()),
              due,
              new EnvironmentName("env-2"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed3.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed3.toolId()),
              due,
              new EnvironmentName("env-stale"));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(toolSeed4.threadId()).orElseThrow();
          tx.requestWork(
              new WorkTarget(WorkTargetType.TOOL, toolSeed4.toolId()),
              due,
              new EnvironmentName("env-connecting"));
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
}
