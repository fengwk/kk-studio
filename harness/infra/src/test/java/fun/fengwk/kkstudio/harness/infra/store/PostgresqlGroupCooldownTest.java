package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

  private void freeze(String member, Instant until) {
    store.transaction(
        tx -> {
          builtin(tx).freezeGroupMember("group", 0L, member, "v1", until);
          return null;
        });
  }

  private static boolean cooling(HarnessStore target, String member) {
    return target.transaction(tx -> builtin(tx).isGroupMemberCooling("group", 0L, member, "v1"));
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
