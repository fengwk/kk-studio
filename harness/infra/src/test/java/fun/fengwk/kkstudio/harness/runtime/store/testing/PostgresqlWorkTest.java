package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class PostgresqlWorkTest extends HarnessStoreWorkContract {

  /** TOOL claim 专用租约：短于 {@link #CLAIM_LEASE}，用于 route affinity 场景。 */
  private static final Duration LEASE_30S = Duration.ofSeconds(30);

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

  /** PostgreSQL 没有可注入时钟：过期必须直接改写持久化行的 lease deadline，由数据库时间域判定。 */
  @Override
  protected void expireLease(WorkTarget target) {
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
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
                        WorkTargetType.THREAD, current, "token-1", Duration.ofSeconds(4)))
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
                tx -> tx.claimNextWork(WorkTargetType.THREAD, claimTime, "token-1", CLAIM_LEASE))
            .orElseThrow();

    // 活跃 lease 阻止其他 claim
    Instant blockedTime = now();
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, blockedTime, "token-2", CLAIM_LEASE))
            .isEmpty());

    expireLease(target);

    Instant reclaimTime = now();
    ClaimedWork reclaimed =
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, reclaimTime, "token-3", CLAIM_LEASE))
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
                tx -> tx.claimNextWork(WorkTargetType.THREAD, current, "token-4", CLAIM_LEASE))
            .isEmpty());
  }

  /** 测试意图：验证 claimNextWork 成功获取带环境亲和性的 TOOL Work 时，返回的 ClaimedWork 正确包含该 requiredEnvironmentId。 */
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
                tx -> tx.claimNextWork(WorkTargetType.TOOL, current, "token", LEASE_30S, node))
            .orElseThrow();
    assertEquals(toolTarget, claimed.target());
    assertEquals(env, claimed.requiredEnvironmentId());
  }

  /**
   * 测试意图：验证 PostgreSQL 下 claimNextWork 对 Environment route 路由围栏的严格守卫：
   *
   * <ul>
   *   <li>无 affinity 约束的 Work 允许任何节点 claim；
   *   <li>有 affinity 约束的 Work 只有持有匹配 environmentId、状态为 READY 且 lease 未过期的 ownerNode 节点可 claim；
   *   <li>连接租约已过期或连接处于非 READY（如 CONNECTING）状态时，严格 fail closed，任何节点均无法 claim；
   *   <li>非目标环境连接持有者节点无法越权 claim。
   * </ul>
   */
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
            tx -> tx.claimNextWork(WorkTargetType.TOOL, current, "token-node3", LEASE_30S, node3));
    assertTrue(claimNode3.isPresent());
    assertEquals(toolSeed5.toolId(), claimNode3.get().target().id());

    // 2. Node3 再次 claim: 无法 claim 任何剩余 tool work
    Optional<ClaimedWork> emptyForNode3 =
        store.transaction(
            tx ->
                tx.claimNextWork(WorkTargetType.TOOL, current, "token-node3-2", LEASE_30S, node3));
    assertTrue(emptyForNode3.isEmpty());

    // 3. Node2 (持有 env-2 READY 路由): 只能 claim 到 tool2 (env-2)
    Optional<ClaimedWork> claimNode2 =
        store.transaction(
            tx -> tx.claimNextWork(WorkTargetType.TOOL, current, "token-node2", LEASE_30S, node2));
    assertTrue(claimNode2.isPresent());
    assertEquals(toolSeed2.toolId(), claimNode2.get().target().id());

    // 4. Node1 (持有 env-1 READY 路由): 只能 claim 到 tool1 (env-1)
    Optional<ClaimedWork> claimNode1 =
        store.transaction(
            tx -> tx.claimNextWork(WorkTargetType.TOOL, current, "token-node1", LEASE_30S, node1));
    assertTrue(claimNode1.isPresent());
    assertEquals(toolSeed1.toolId(), claimNode1.get().target().id());

    // 5. 剩余 tool3 (stale lease) 和 tool4 (CONNECTING): 任何 node 都无法 claim
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL, current, "token-node1-2", LEASE_30S, node1))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.TOOL, current, "token-node2-2", LEASE_30S, node2))
            .isEmpty());
  }

  /**
   * 测试意图：生产权威时间是数据库时钟——claim 写出的 lease deadline 必须由数据库时间计算（≈ duration），而不是直接采用调用方 JVM 的绝对
   * Instant；JVM 相对数据库偏移 ±2x lease duration 时不改变 claim 结果、lease fence 判定与 renew 语义。
   */
  @Test
  void leaseAuthorityIsDatabaseTimeUnderJvmClockSkew() {
    HarnessStore store = createStore();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          // available_at 只是调度时间：写一个远过去的时间，避免 JVM 与数据库时钟关系影响 due 判定
          tx.requestWork(target, Instant.ofEpochMilli(1000));
        });

    Duration lease = Duration.ofMinutes(10);
    Instant jvmNow = now();
    Instant jvmAhead = jvmNow.plus(lease.multipliedBy(2));
    Instant jvmBehind = jvmNow.minus(lease.multipliedBy(2));

    // JVM 超前 2x：claim 仍成功，deadline 只由数据库时间决定（≈ lease，而不是 lease + 2x skew）
    ClaimedWork claimed =
        store
            .transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, jvmAhead, "token", lease))
            .orElseThrow();
    assertEquals(lease.toMillis(), remainingLeaseMillis(jdbc, target.id()), 5_000L);

    // 两个方向的 JVM 偏差都不参与 fence：数据库时间域内 lease 同时有效
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claimed, jvmAhead)).isPresent());
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claimed, jvmBehind)).isPresent());

    // JVM 滞后 2x 时 renew 仍按数据库时间延长（而不是缩短或失效）
    long before = remainingLeaseMillis(jdbc, target.id());
    boolean extended =
        store.transaction(tx -> tx.renewWork(claimed, jvmBehind, lease.multipliedBy(2)));
    assertTrue(extended);
    long after = remainingLeaseMillis(jdbc, target.id());
    assertTrue(after > before, "renew must extend the database-time lease");
    assertEquals(lease.multipliedBy(2).toMillis(), after, 5_000L);

    // 数据库时间域内过期后，旧 claim 立即失去全部 ownership
    expireLease(target);
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(claimed, jvmAhead)).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.completeWork(claimed, jvmAhead)));
  }

  /** 数据库时间域中 target 当前 lease 的剩余毫秒数：断言 deadline 由数据库时钟而非 JVM 时钟决定。 */
  private static long remainingLeaseMillis(JdbcTemplate jdbc, UUID targetId) {
    Double milliseconds =
        jdbc.queryForObject(
            """
            select extract(epoch from (lease_until - statement_timestamp())) * 1000
            from harness_work
            where target_type = 'THREAD' and target_id = ?
            """,
            Double.class,
            targetId);
    return milliseconds == null ? 0L : Math.round(milliseconds);
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
