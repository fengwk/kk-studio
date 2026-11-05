package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.platform.catalog.model.GroupCooldownRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 真实 PostgreSQL 组冷却版本围栏、并发延长与跨 store 共享语义。 */
class PostgresqlGroupCooldownTest {

  private HarnessStore store;
  private final GroupCooldownRepository cooldowns = new GroupCooldownRepository();

  /** 冷却按数据库事务时间判断：截止时间都相对测试开始时的数据库时间。 */
  private Instant now;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    seedCatalog();
    now = store.transaction(HarnessStore.Transaction::now);
  }

  /** 组配置版本更新并删除冷却后，旧版本 callback 不得复活或延长新配置冷却。 */
  @Test
  void staleCallbackCannotReviveCooldownAfterGroupVersionUpdate() {
    freeze("model", now.plusSeconds(30));
    assertTrue(cooling(store, "model"));

    // 模拟 catalog writer 的合法 group config 修改事务：升版本并清除旧冷却。
    execute(
        "update agent_model set version = 1, updated_at = updated_at + interval '1 millisecond'"
            + " where name = 'group'",
        "delete from agent_model_group_cooldown where group_name = 'group'");

    freeze("model", now.plusSeconds(60));

    assertEquals(0, cooldownRows());
    assertFalse(cooling(store, "model"));
  }

  /** 并发旧 callback 必须等待 catalog writer 的 agent_model 行锁；提交后看到新版本即放弃写入，且不死锁。 */
  @Test
  void concurrentStaleCallbackWaitsForCatalogWriterAndCannotWriteNewVersion() throws Exception {
    CountDownLatch updated = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch callbackDone = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> writer =
          executor.submit(
              () -> {
                try (Connection connection =
                    PostgresqlHarnessStoreFixture.dataSource().getConnection()) {
                  connection.setAutoCommit(false);
                  try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                        "update agent_model set version = 1,"
                            + " updated_at = updated_at + interval '1 millisecond'"
                            + " where name = 'group'");
                    statement.executeUpdate(
                        "delete from agent_model_group_cooldown where group_name = 'group'");
                  }
                  updated.countDown();
                  if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("writer release timed out");
                  }
                  connection.commit();
                } catch (SQLException error) {
                  throw new IllegalStateException(error);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
                return null;
              });
      assertTrue(updated.await(10, TimeUnit.SECONDS));
      Future<?> callback =
          executor.submit(
              () -> {
                freeze("model", now.plusSeconds(60));
                callbackDone.countDown();
                return null;
              });

      assertFalse(callbackDone.await(300, TimeUnit.MILLISECONDS));
      release.countDown();
      writer.get(10, TimeUnit.SECONDS);
      callback.get(10, TimeUnit.SECONDS);
    }
    assertEquals(0, cooldownRows());
  }

  /** 同一版本的并发失败只延长截止时间，较晚/较早写入竞争都不会缩短已有冷却。 */
  @Test
  void concurrentFailuresOnlyExtendCooldownDeadline() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> shorter =
          executor.submit(
              () -> {
                start.await();
                freeze("model", now.plusSeconds(10));
                return null;
              });
      Future<?> longer =
          executor.submit(
              () -> {
                start.await();
                freeze("model", now.plusSeconds(30));
                return null;
              });
      start.countDown();
      shorter.get(10, TimeUnit.SECONDS);
      longer.get(10, TimeUnit.SECONDS);
    }
    assertEquals(now.plusSeconds(30), cooldownUntil());
  }

  /** 冷却存储在 PostgreSQL 而非进程内：新 store 实例读到同一冻结事实，过期截止时间不再冷却。 */
  @Test
  void restartedStoreReadsSharedCooldown() {
    freeze("model", now.plusSeconds(30));
    freeze("model2", now.minusSeconds(1));
    HarnessStore restarted = PostgresqlHarnessStoreFixture.create();

    assertTrue(cooling(restarted, "model"));
    assertFalse(cooling(restarted, "model2"));
  }

  /** 回滚事务不得留下组冷却，即使同一事务内已经能够读到它。 */
  @Test
  void cooldownRollsBackWithCallerTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx -> {
                  cooldowns.freeze(tx, "group", 0L, "model", "v1", now.plusSeconds(30));
                  assertTrue(cooldowns.isCooling(tx, "group", 0L, "model", "v1"));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, cooldownRows());
  }

  /** 冷却、invocation 和 work 都经过同一物理事务，失败时共同回滚。 */
  @Test
  void cooldownInvocationAndWorkChangesRollbackTogether() {
    var turn = seedTurnBaseline(store);
    var modelId = id(100L);
    var target = new WorkTarget(BuiltinWorkType.MODEL, modelId);
    store.transaction(
        tx -> {
          tx.threads().lock(turn.threadId()).orElseThrow();
          builtin(store, tx)
              .insertModelInvocation(
                  modelInvocation(
                      modelId,
                      turn.threadId(),
                      turn.turnStartEntryId(),
                      turn.turnStartEntryId(),
                      ModelInvocationStatus.READY,
                      null,
                      T1));
          tx.work().request(target);
          return null;
        });
    var claim =
        store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(BuiltinWorkType.MODEL, UUID.randomUUID(), Duration.ofMinutes(1)))
            .orElseThrow();
    var before = store.transaction(tx -> tx.work().find(target).orElseThrow());
    assertThrows(
        IllegalStateException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.threads().lock(turn.threadId()).orElseThrow();
                  var model = builtin(store, tx).lockModelInvocation(modelId).orElseThrow();
                  tx.work().lockClaimed(claim).orElseThrow();
                  cooldowns.freeze(tx, "group", 0L, "model", "v1", tx.now().plusSeconds(30));
                  builtin(store, tx).updateModelInvocation(model.markRunning(tx.now()));
                  tx.work().reschedule(claim, Duration.ofSeconds(5));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, cooldownRows());
    assertEquals(before, store.transaction(tx -> tx.work().find(target).orElseThrow()));
    assertEquals(
        ModelInvocationStatus.READY,
        store.transaction(
            tx -> builtin(store, tx).findModelInvocation(modelId).orElseThrow().status()));
  }

  /** 调用方误吞冷却 SQL 异常也不能提交该事务内的其他写入。 */
  @Test
  void swallowedCooldownDatabaseFailurePoisonsTransaction() {
    var turn = seedTurnBaseline(store);
    var target = new WorkTarget(WorkTargetType.THREAD, turn.threadId());
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.threads().lock(turn.threadId()).orElseThrow();
                  tx.work().request(target);
                  try {
                    cooldowns.freeze(tx, "group", 0L, "missing", "v1", tx.now().plusSeconds(30));
                  } catch (IllegalArgumentException expected) {
                    // 故意吞掉失败，事务核心仍必须拒绝提交。
                  }
                  return null;
                }));
    assertTrue(store.transaction(tx -> tx.work().find(target)).isEmpty());
    assertEquals(0, cooldownRows());
  }

  private void freeze(String member, Instant until) {
    store.transaction(
        tx -> {
          cooldowns.freeze(tx, "group", 0L, member, "v1", until);
          return null;
        });
  }

  private boolean cooling(HarnessStore target, String member) {
    return target.transaction(tx -> cooldowns.isCooling(tx, "group", 0L, member, "v1"));
  }

  private static int cooldownRows() {
    try (Connection connection = PostgresqlHarnessStoreFixture.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("select count(*) from agent_model_group_cooldown")) {
      result.next();
      return result.getInt(1);
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Instant cooldownUntil() {
    try (Connection connection = PostgresqlHarnessStoreFixture.dataSource().getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "select cooldown_until from agent_model_group_cooldown"
                    + " where group_name = 'group' and member_model_name = 'model'")) {
      assertTrue(result.next());
      return result.getTimestamp(1).toInstant();
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void seedCatalog() {
    try (Connection connection = PostgresqlHarnessStoreFixture.dataSource().getConnection()) {
      try (PreparedStatement provider =
          connection.prepareStatement(
              "insert into agent_provider"
                  + " (name, provider_type, config, connection_generation_id)"
                  + " values ('provider', 'openai', '{}'::jsonb, ?)")) {
        provider.setObject(1, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        provider.executeUpdate();
      }
      execute(
          "insert into agent_model (name, kind, provider_name, model_id, config)"
              + " values ('model', 'MODEL', 'provider', 'model', '{}'::jsonb)",
          "insert into agent_model (name, kind, provider_name, model_id, config)"
              + " values ('model2', 'MODEL', 'provider', 'model2', '{}'::jsonb)",
          "insert into agent_model (name, kind, group_config)"
              + " values ('group', 'GROUP', '{}'::jsonb)");
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void execute(String... statements) {
    try (Connection connection = PostgresqlHarnessStoreFixture.dataSource().getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.executeUpdate(sql);
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }
}
