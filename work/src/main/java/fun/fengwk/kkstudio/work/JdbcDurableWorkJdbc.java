package fun.fengwk.kkstudio.work;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 绑定 Spring JDBC 与 {@code PROPAGATION_REQUIRED} 短事务的默认钩子。 */
final class JdbcDurableWorkJdbc implements DurableWorkJdbc {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  JdbcDurableWorkJdbc(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setName("durable-work");
  }

  @Override
  public <T> Optional<T> queryOne(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbc.sql(sql).params(Arrays.asList(arguments)).query(rowMapper).optional();
  }

  @Override
  public int update(String sql, Object... arguments) {
    return jdbc.sql(sql).params(Arrays.asList(arguments)).update();
  }

  @Override
  public <T> T inTransaction(Supplier<T> callback) {
    return transactions.execute(status -> callback.get());
  }
}
