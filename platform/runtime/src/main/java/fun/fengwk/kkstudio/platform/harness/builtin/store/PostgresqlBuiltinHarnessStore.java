package fun.fengwk.kkstudio.platform.harness.builtin.store;

import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessStore;

import javax.sql.DataSource;

import java.util.UUID;
import java.util.function.Supplier;

/** 把 Builtin 私有存储装配到 Harness 的同一 PostgreSQL 事务中。 */
public final class PostgresqlBuiltinHarnessStore extends PostgresqlHarnessStore
    implements BuiltinStore {

  public PostgresqlBuiltinHarnessStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      Supplier<UUID> idGenerator) {
    super(dataSource, transactionManager, idGenerator, PostgresqlModelTransaction::new);
  }

  @Override
  public BuiltinStore.Transaction in(HarnessStore.Transaction tx) {
    return (BuiltinStore.Transaction) driverView(tx);
  }
}
