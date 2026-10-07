package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class PostgresqlWorkTest extends HarnessStoreWorkContract {

  /** TOOL claim 专用租约：短于 {@link #CLAIM_LEASE}，用于 route affinity 场景。 */
  private static final Duration LEASE_30S = Duration.ofSeconds(30);

  private JdbcTemplate jdbc;

  /** 每调用只取自身未来边界：在线租约 / future due / active Work lease，PG least 忽略 null。 */
  @Test
  void toolProjectionUsesPerInvocationDeadlinesAndDatabaseTime() {
    SeededTools seeded = seedTools(store, 3);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          for (UUID id : seeded.toolIds()) {
            tx.requestWork(
                new WorkTarget(WorkTargetType.TOOL, id), authorityNow(), WAITING_ENVIRONMENT);
          }
        });
    jdbc.update(
        "update environment set name = 'frozen-env' where id = ?", WAITING_ENVIRONMENT.value());
    seedReadyEnvironmentLease(WAITING_ENVIRONMENT);
    Instant environmentDeadline =
        jdbc.queryForObject(
            "select lease_until from environment_connection where environment_id = ?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            WAITING_ENVIRONMENT.value());
    UUID online = seeded.toolIds().get(0);
    UUID future = seeded.toolIds().get(1);
    UUID leased = seeded.toolIds().get(2);
    forceWorkAvailableAfter(new WorkTarget(WorkTargetType.TOOL, future), Duration.ofMinutes(5));
    forceWorkAvailableAfter(new WorkTarget(WorkTargetType.TOOL, leased), Duration.ofMinutes(15));
    jdbc.update(
        """
        update harness_work set lease_token = 'projection-lease',
            lease_until = statement_timestamp() + interval '10 minutes'
        where target_type = 'TOOL' and target_id = ?
        """,
        leased);
    // 应用时钟错到未来仍按同一 SQL statement_timestamp 判断，而不是误认为全部已过期。
    var rows =
        store.transaction(
            tx ->
                tx.listEnvironmentToolWaits(
                    Instant.parse("2099-01-01T00:00:00Z"), seeded.toolIds()));
    for (var row : rows) {
      assertEquals("frozen-env", row.environmentName());
      assertFalse(row.waitingForEnvironment());
      Instant expected =
          row.invocationId().equals(online)
              ? environmentDeadline
              : store
                  .transaction(
                      tx -> tx.findWork(new WorkTarget(WorkTargetType.TOOL, row.invocationId())))
                  .map(
                      work ->
                          row.invocationId().equals(future)
                              ? work.availableAt()
                              : work.leaseUntil())
                  .orElseThrow();
      assertEquals(expected, row.freshnessAt());
    }
    expireReadyEnvironmentLease(WAITING_ENVIRONMENT);
    var offline =
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(online)))
            .getFirst();
    assertTrue(offline.waitingForEnvironment());
    assertNull(offline.freshnessAt());
    // CONNECTING 即使租约有效也不作为环境 READY 边界。
    seedReadyEnvironmentLease(WAITING_ENVIRONMENT);
    jdbc.update(
        "update environment_connection set status = 'CONNECTING' where environment_id = ?",
        WAITING_ENVIRONMENT.value());
    assertNull(
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(online)))
            .getFirst()
            .freshnessAt());
    forceWorkAvailable(new WorkTarget(WorkTargetType.TOOL, future));
    expireLease(new WorkTarget(WorkTargetType.TOOL, leased));
    forceWorkAvailable(new WorkTarget(WorkTargetType.TOOL, leased));
    assertTrue(
        store
            .transaction(tx -> tx.listEnvironmentToolWaits(authorityNow(), seeded.toolIds()))
            .stream()
            .allMatch(row -> row.waitingForEnvironment() && row.freshnessAt() == null));
    assertEquals(
        List.of(),
        store.transaction(
            tx -> tx.listEnvironmentToolWaits(authorityNow(), List.of(UUID.randomUUID()))));
  }

  @Override
  HarnessStore createStore() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    seedEnvironment(jdbc, EnvironmentId.parse("11111111-1111-1111-1111-111111111111"));
    seedEnvironment(jdbc, EnvironmentId.parse("22222222-2222-2222-2222-222222222222"));
    seedEnvironment(jdbc, EnvironmentId.parse("33333333-3333-3333-3333-333333333333"));
    seedEnvironment(jdbc, EnvironmentId.parse("44444444-4444-4444-4444-444444444444"));
    return store;
  }

  private static Instant now() {
    return Instant.ofEpochMilli(System.currentTimeMillis());
  }

  /** 生产实现的权威时间是数据库时钟：契约的 authorityNow 只是取数据库「此刻」快照。 */
  @Override
  protected Instant authorityNow() {
    return jdbc.queryForObject(
        "select date_trunc('milliseconds', statement_timestamp())",
        (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant());
  }

  /** PostgreSQL 没有可注入时钟：过期必须直接改写持久化行的 lease deadline，由数据库时间域判定。 */
  @Override
  protected void expireLease(WorkTarget target) {
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

  /** 生产权威时间是数据库时钟：到期只能直接改写持久化行的 available_at，不依赖 JVM 时钟。 */
  @Override
  protected void forceWorkAvailable(WorkTarget target) {
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
  }

  @Override
  protected void forceWorkAvailableAfter(WorkTarget target, Duration delay) {
    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set available_at = statement_timestamp() + ?::interval
            where target_type = ? and target_id = ?
            """,
            delay.toMillis() + " milliseconds",
            target.type().name(),
            target.id()));
  }

  @Override
  protected void seedReadyEnvironmentLease(EnvironmentId environmentId) {
    assertEquals(
        1,
        jdbc.update(
            """
            insert into environment_connection (
                environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until
            ) values (
                ?, gen_random_uuid(), gen_random_uuid(), 'READY', '{}'::jsonb,
                statement_timestamp(), statement_timestamp() + interval '1 hour'
            )
            on conflict (environment_id) do update
            set owner_node_id = excluded.owner_node_id,
                lease_token = excluded.lease_token,
                status = excluded.status,
                runtime_info = excluded.runtime_info,
                last_seen_at = excluded.last_seen_at,
                lease_until = excluded.lease_until
            """,
            environmentId.value()));
  }

  @Override
  protected void expireReadyEnvironmentLease(EnvironmentId environmentId) {
    assertEquals(
        1,
        jdbc.update(
            """
            update environment_connection
            set lease_until = statement_timestamp() - interval '1 second',
                last_seen_at = statement_timestamp() - interval '2 seconds'
            where environment_id = ?
            """,
            environmentId.value()));
  }

  /** 生产实现的权威时间是数据库时钟：requestWork 一律写入「此刻」，因此只能直接改写持久化 available_at 来构造确定的 due 顺序。 */
  @Test
  @Override
  void claimNextWorkIsOrderedByAvailableAtThenTargetId() {
    HarnessStore store = createStore();
    Baseline baseline = seedThreadBaseline(store);
    UUID thread2 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.siblingRoot(
                      id, baseline.sessionId(), baseline.rootEntryId(), "sibling-2"));
              return id;
            });
    UUID thread3 =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.siblingRoot(
                      id, baseline.sessionId(), baseline.rootEntryId(), "sibling-3"));
              return id;
            });

    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.lockThread(thread2).orElseThrow();
          tx.lockThread(thread3).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), now());
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread2), now());
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread3), now());
        });

    // 改写数据库 available_at 制造确定顺序：baseline(-11s) < thread3(-10s) < thread2(-5s)。
    setAvailableAtSecondsAgo(baseline.threadId(), 11);
    setAvailableAtSecondsAgo(thread3, 10);
    setAvailableAtSecondsAgo(thread2, 5);

    Instant current = now();
    assertEquals(baseline.threadId(), claimNext(WorkTargetType.THREAD, current).target().id());
    assertEquals(thread3, claimNext(WorkTargetType.THREAD, current).target().id());
    assertEquals(thread2, claimNext(WorkTargetType.THREAD, current).target().id());
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, current, "token-4", CLAIM_LEASE))
            .isEmpty());
  }

  private void setAvailableAtSecondsAgo(UUID targetId, int seconds) {
    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set available_at = statement_timestamp() - make_interval(secs => ?)
            where target_type = 'THREAD' and target_id = ?
            """,
            seconds,
            targetId));
  }

  /** 测试意图：验证 claimNextWork 成功获取带环境亲和性的 TOOL Work 时，返回的 ClaimedWork 正确包含该 requiredEnvironmentId。 */
  @Test
  @Override
  void claimNextWorkReturnsClaimedWorkWithEnvironmentAffinity() {
    HarnessStore store = createStore();
    UUID node = UUID.randomUUID();
    EnvironmentId env = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    seedEnvironmentConnection(jdbc, env, "daemon-1", node, "READY");

    SeededTool seeded = seedTool(store);
    WorkTarget toolTarget = new WorkTarget(WorkTargetType.TOOL, seeded.toolId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(seeded.threadId()).orElseThrow();
          tx.requestWork(toolTarget, now(), env);
        });

    ClaimedWork claimed =
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.TOOL, now(), "token", LEASE_30S, node))
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
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    Duration lease = Duration.ofMinutes(10);
    Instant jvmNow = now();
    Instant jvmAhead = jvmNow.plus(lease.multipliedBy(2));
    Instant jvmBehind = jvmNow.minus(lease.multipliedBy(2));

    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          // accept 是「立即」：available_at 由数据库权威时间写入，JVM 传入的 now 不参与 due。
          tx.requestWork(target, jvmAhead);
        });

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

  /**
   * 测试意图：accept（requestWork）与 retry（rescheduleWork）的 due 都由数据库权威时间决定——JVM 相对数据库偏移 ±2x delay 时既不延迟
   * accept 后的立即 claim，也不改变 retry 的真实数据库 due 相对 delay 的差值。
   */
  @Test
  void acceptAndRescheduleUseDatabaseDueUnderJvmClockSkew() {
    HarnessStore store = createStore();
    Baseline baseline = seedThreadBaseline(store);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    Duration skew = Duration.ofMinutes(30);
    Duration retryDelay = Duration.ofMinutes(3);
    Instant jvmNow = now();
    Instant jvmAhead = jvmNow.plus(skew.multipliedBy(2));
    Instant jvmBehind = jvmNow.minus(skew.multipliedBy(2));

    // JVM 超前 2x：accept 仍是「立即」，数据库 due 与「此刻」的差约为 0（不因 skew 延后）。
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.requestWork(target, jvmAhead);
        });
    assertEquals(0L, remainingDueWaitMillis(jdbc, target.id()), 1_000L);
    ClaimedWork claim =
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, jvmAhead, "token", CLAIM_LEASE))
            .orElseThrow();

    // JVM 滞后 2x：retry 的数据库 due 恰为 delay（而不是 delay ± skew），且此刻尚未到期。
    inTransaction(store, tx -> tx.rescheduleWork(claim, jvmBehind, retryDelay));
    assertEquals(retryDelay.toMillis(), remainingDueWaitMillis(jdbc, target.id()), 1_000L);
    assertTrue(
        store
            .transaction(
                tx -> tx.claimNextWork(WorkTargetType.THREAD, jvmBehind, "token-2", CLAIM_LEASE))
            .isEmpty());
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

  /** 数据库时间域中 target 当前 available_at 距「此刻」的毫秒数：断言 due 由数据库时钟而非 JVM 时钟决定。 */
  private static long remainingDueWaitMillis(JdbcTemplate jdbc, UUID targetId) {
    Double milliseconds =
        jdbc.queryForObject(
            """
            select extract(epoch from (available_at - statement_timestamp())) * 1000
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
