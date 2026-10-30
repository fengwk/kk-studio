package fun.fengwk.kkstudio.work;

import org.springframework.jdbc.core.RowMapper;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link DurableWorkStore} 的 SQL 执行与事务边界钩子。
 *
 * <p>生产默认实现绑定 Spring {@code JdbcClient} 与 {@code PlatformTransactionManager}；Harness 用它的事务核心实现本接口，使
 * {@code durable_work} SQL 与其它聚合共享同一物理连接，并让底层 JDBC 失败经过 Harness 的首错 poisoning 边界。
 *
 * <p>{@link #inTransaction(Supplier)} 只允许加入调用方已有事务；没有事务时由实现开启短事务。禁止 {@code REQUIRES_NEW}。
 */
public interface DurableWorkJdbc {

  /** 执行至多返回一行的查询；底层异常必须原样抛出。 */
  <T> Optional<T> queryOne(String sql, RowMapper<T> rowMapper, Object... arguments);

  /** 执行更新并返回影响行数；底层异常必须原样抛出。 */
  int update(String sql, Object... arguments);

  /** 在调用方物理事务中执行；没有外层事务时开启短事务。已处于该实现自己的事务内时直接执行，禁止嵌套。 */
  <T> T inTransaction(Supplier<T> callback);

  /** 当前调用是否已经处在本钩子代表的物理事务中。 */
  boolean transactionActive();
}
