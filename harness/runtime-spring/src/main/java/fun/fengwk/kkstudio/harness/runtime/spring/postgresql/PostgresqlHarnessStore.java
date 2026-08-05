package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.util.Objects;
import java.util.function.Function;

/**
 * PostgreSQL implementation of the seven-table {@link HarnessStore} protocol.
 *
 * <p>Each store callback owns one independent READ_COMMITTED database transaction. A REQUIRES_NEW
 * boundary guarantees that a successful return means the durable commit has completed, even when
 * the caller already has an unrelated Spring transaction.
 */
public final class PostgresqlHarnessStore implements HarnessStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ThreadLocal<Boolean> active = new ThreadLocal<>();

  public PostgresqlHarnessStore(DataSource dataSource) {
    DataSource requiredDataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(requiredDataSource);
    this.transactions =
        new TransactionTemplate(new DataSourceTransactionManager(requiredDataSource));
    this.transactions.setName("harness-store");
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

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
            PostgresqlHarnessTransaction transaction = new PostgresqlHarnessTransaction(jdbc);
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
