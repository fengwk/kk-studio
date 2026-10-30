package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.work.DurableWorkJdbc;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把 {@code durable_work} SQL 接到 Harness 事务核心。
 *
 * <p>查询与更新走同一 {@link PostgresqlHarnessTransaction} 连接，因此与 Thread、Driver、Tool 写入共享物理事务；底层 JDBC
 * 失败由核心记录为首个数据库故障。 调用方已经处在该事务内，{@link #inTransaction(Supplier)} 不再开启新事务。
 */
final class PostgresqlDurableWorkJdbc implements DurableWorkJdbc {

  private final PostgresqlHarnessTransaction tx;

  PostgresqlDurableWorkJdbc(PostgresqlHarnessTransaction tx) {
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  @Override
  public <T> Optional<T> queryOne(String sql, RowMapper<T> rowMapper, Object... arguments) {
    tx.checkOpen();
    return tx.queryOne(sql, rowMapper, arguments);
  }

  @Override
  public int update(String sql, Object... arguments) {
    tx.checkOpen();
    return tx.updateJdbc(sql, arguments);
  }

  @Override
  public <T> T inTransaction(Supplier<T> callback) {
    tx.checkOpen();
    return callback.get();
  }

  @Override
  public boolean transactionActive() {
    tx.checkOpen();
    return true;
  }
}
