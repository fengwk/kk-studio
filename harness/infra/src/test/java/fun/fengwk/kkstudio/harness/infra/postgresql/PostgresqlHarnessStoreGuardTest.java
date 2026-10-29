package fun.fengwk.kkstudio.harness.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Builtin typed view 的 Store 归属、owner thread 与事务生命周期守卫。
 *
 * <p>本测试使用不访问数据库的 transaction manager，直接验证 view 在触达 SQL 之前 fail closed；真实 JDBC 同连接行为由 PostgreSQL
 * 集成测试覆盖。
 */
class PostgresqlHarnessStoreGuardTest {

  private static final PlatformTransactionManager NOOP_TRANSACTIONS =
      new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
          return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
      };

  @Test
  void typedViewSharesTheRuntimeTransactionAndRejectsCrossStoreCrossThreadAndEscapedUse()
      throws Exception {
    PostgresqlHarnessStore store = newStore();
    PostgresqlHarnessStore otherStore = newStore();
    BuiltinStore.Transaction[] escaped = new BuiltinStore.Transaction[1];

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      store.transaction(
          tx -> {
            BuiltinStore.Transaction builtin = store.in(tx);
            assertSame(builtin, store.in(tx));
            escaped[0] = builtin;

            assertThrows(IllegalArgumentException.class, () -> otherStore.in(tx));
            Future<BuiltinStore.Transaction> crossThread = executor.submit(() -> store.in(tx));
            ExecutionException failure = assertThrows(ExecutionException.class, crossThread::get);
            assertSame(IllegalStateException.class, failure.getCause().getClass());
            return null;
          });
    }

    assertThrows(
        IllegalStateException.class, () -> escaped[0].findModelInvocation(UUID.randomUUID()));
  }

  private static PostgresqlHarnessStore newStore() {
    return new PostgresqlHarnessStore(
        new DriverManagerDataSource(), NOOP_TRANSACTIONS, () -> UUID.randomUUID());
  }
}
