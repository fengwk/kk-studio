package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 七表 {@link HarnessStore} durable protocol 的 PostgreSQL 实现。
 *
 * <p>每个 store callback 在 {@code READ_COMMITTED} 事务内执行。{@code PROPAGATION_REQUIRED}
 * 边界允许调用方已有的外层事务加入（同连接同事务）； 回调正常返回即表示 durable commit 已完成，除非外层事务随后回滚。
 *
 * <p>同一 Store 实例通过 {@link ThreadLocal} 显式拒绝嵌套事务（nested transactions are not supported）， 并在 callback
 * 正常执行完毕后统一触发 {@link PostgresqlHarnessTransaction#rethrowDatabaseFailure()} 实施首个数据库故障 poisoning 校验。
 */
public final class PostgresqlHarnessStore implements HarnessStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Supplier<UUID> idGenerator;
  private final ThreadLocal<Boolean> active = new ThreadLocal<>();

  public PostgresqlHarnessStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      Supplier<UUID> idGenerator) {
    DataSource requiredDataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(requiredDataSource);
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setName("harness-store");
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  /**
   * 在受管的 PostgreSQL 事务边界中执行 Store 操作回调。
   *
   * @param callback 接收 {@link HarnessStore.Transaction} 并产生结果的操作函数
   * @param <T> 返回值类型
   * @return 回调返回值
   * @throws IllegalStateException 若当前线程在同一 Store 实例中尝试嵌套开启事务
   */
  @Override
  public <T> T transaction(Function<Transaction, T> callback) {
    Objects.requireNonNull(callback, "callback");
    if (Boolean.TRUE.equals(active.get())) {
      throw new IllegalStateException("nested transactions are not supported");
    }
    active.set(Boolean.TRUE);
    try {
      return transactions.execute(
          status -> {
            PostgresqlHarnessTransaction transaction =
                new PostgresqlHarnessTransaction(jdbc, idGenerator);
            try {
              T result = callback.apply(transaction);
              transaction.rethrowDatabaseFailure();
              return result;
            } finally {
              transaction.close();
            }
          });
    } finally {
      active.remove();
    }
  }
}
