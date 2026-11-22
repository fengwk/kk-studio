package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinRegistry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Join 乐观 CAS 未命中的诊断语义：并发改写与记录不存在必须可区分。
 *
 * <p>用只在生产 CAS 语句写入前提交一次竞争改动的 {@link DataSource} 探针，确定性地制造「已读前像、写入前被并发改写」的窗口，不靠延时制造竞态； 竞争改动走真实
 * PostgreSQL 连接与自动提交事务。
 */
class PostgresqlJoinCasMissTest {

  /** 生产 Join CAS 语句的可识别前缀：探针据此只在真正的 CAS 写入前插入竞争改动。 */
  private static final String CAS_UPDATE_MARKER = "update harness_thread_join";

  private static final String CAS_UPDATE_SQL =
      "update harness_thread_join set terminal_entry_id = ? where invocation_id = ?";

  private static final String DELETE_SQL =
      "delete from harness_thread_join where invocation_id = ?";

  private HarnessStore seedStore;
  private HarnessRuntime runtime;
  private DataSource dataSource;

  @BeforeEach
  void setUp() {
    seedStore = PostgresqlHarnessStoreFixture.resetAndCreate();
    dataSource = PostgresqlHarnessStoreFixture.dataSource();
    runtime =
        new HarnessRuntime(
            seedStore,
            builtinRegistry(seedStore, (threadId, rootSessionId, path, preparation) -> null),
            () -> new CompactionConfig(20_000));
  }

  @Test
  void concurrentModificationFailsAsConflictNotMissingRow() {
    // 测试意图：CAS 的 0 行来自并发改写时必须表达并发冲突语义，不能把「被并发改写」误报成「join 不存在」。
    SeededJoin seeded = seedUnmatchedJoin();
    Race race =
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(CAS_UPDATE_SQL)) {
            statement.setObject(1, seeded.rootEntryId());
            statement.setObject(2, seeded.invocationId());
            // 竞争改写确实命中同一行：证明随后的 0 行更新来自并发 CAS 未命中，而不是行缺失。
            assertEquals(1, statement.executeUpdate());
          }
        };

    HarnessStore racingStore = PostgresqlHarnessStoreFixture.create(racingDataSource(race));

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> remindOnce(racingStore, seeded));

    String message = failure.getMessage().toLowerCase(Locale.ROOT);
    assertFalse(message.contains("does not exist"), "CAS 未命中不得报成记录不存在：" + message);
    assertTrue(
        message.contains("concurrent") || message.contains("conflict"),
        "CAS 未命中必须给出并发冲突语义：" + message);
    // 行仍然存在：这次 0 行更新不是删除路径。
    assertTrue(seedStore.transaction(tx -> tx.joins().find(seeded.invocationId())).isPresent());
  }

  @Test
  void concurrentDeletionStillFailsAsMissingRow() {
    // 测试意图：真正被并发删掉的 join 仍报「不存在」，守住 0 行更新的另一半来源。
    SeededJoin seeded = seedUnmatchedJoin();
    Race race =
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setObject(1, seeded.invocationId());
            assertEquals(1, statement.executeUpdate());
          }
        };

    HarnessStore racingStore = PostgresqlHarnessStoreFixture.create(racingDataSource(race));

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> remindOnce(racingStore, seeded));

    assertTrue(
        failure.getMessage().contains("does not exist"),
        "被并发删除的 join 仍是不存在语义：" + failure.getMessage());
  }

  /** 走生产 CAS：先锁子 Thread，再在读到的前像上推进 reminder；未匹配 Join 只校验身份与回执前置。 */
  private static void remindOnce(HarnessStore store, SeededJoin seeded) {
    store.transaction(
        tx -> {
          tx.threads().lock(seeded.childThreadId());
          ThreadJoin join = tx.joins().find(seeded.invocationId()).orElseThrow();
          tx.joins().update(join.remind(1L, T2));
          return null;
        });
  }

  /** 已提交的 Join 种子：父执行根下一份未匹配的 NOTIFY_PARENT Join。 */
  private record SeededJoin(UUID rootEntryId, UUID childThreadId, UUID invocationId) {}

  private SeededJoin seedUnmatchedJoin() {
    Baseline baseline = seedThreadBaseline(seedStore);
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    SpawnTestSupport.acceptAndJoin(
        runtime,
        new SpawnTestSupport.Child(
            UUID.randomUUID(),
            childThreadId,
            baseline.threadId(),
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(AgentMessage.user("prompt")),
                    UUID.randomUUID()))),
        SpawnTestSupport.Ticket.task(
            invocationId,
            baseline.threadId(),
            baseline.rootEntryId(),
            CREATION_REQUEST_HASH,
            "test-agent",
            5,
            3,
            3,
            3),
        AcceptancePreflight.IDENTITY);
    return new SeededJoin(baseline.rootEntryId(), childThreadId, invocationId);
  }

  /** 竞争改动：在独立连接上以自动提交方式提交一次对同一 Join 行的改写或删除。 */
  @FunctionalInterface
  private interface Race {

    void run(Connection connection) throws SQLException;
  }

  /**
   * 包一层 DataSource：生产 Join CAS 语句准备执行时（已读完前像、尚未写入），在独立连接上提交一次竞争改动。
   *
   * <p>竞争只触发一次，其余语句按原样委托，保证被测事务自身的读写仍走生产 SQL 与真实 PostgreSQL 行锁、READ COMMITTED 语义。
   */
  private DataSource racingDataSource(Race race) {
    AtomicBoolean fired = new AtomicBoolean();
    return (DataSource)
        Proxy.newProxyInstance(
            PostgresqlJoinCasMissTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(dataSource, method, args);
              if (result instanceof Connection connection) {
                return racingConnection(connection, race, fired);
              }
              return result;
            });
  }

  private Connection racingConnection(Connection target, Race race, AtomicBoolean fired) {
    return (Connection)
        Proxy.newProxyInstance(
            PostgresqlJoinCasMissTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (isCasPreparation(method, args) && fired.compareAndSet(false, true)) {
                runRace(race);
              }
              return invoke(target, method, args);
            });
  }

  private static boolean isCasPreparation(Method method, Object[] args) {
    return method.getName().equals("prepareStatement")
        && args != null
        && args.length > 0
        && args[0] instanceof String sql
        && sql.toLowerCase(Locale.ROOT).contains(CAS_UPDATE_MARKER);
  }

  /** 用真实测试库的另一条自动提交连接提交竞争改动。 */
  private void runRace(Race race) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      race.run(connection);
    }
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getTargetException();
    }
  }
}
