package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 七表 {@link HarnessStore} durable protocol 的 PostgreSQL 实现。
 *
 * <p>每个 store callback 在 {@code READ_COMMITTED} 事务内执行。{@code PROPAGATION_REQUIRED}
 * 边界允许调用方已有的外层事务加入（同连接同事务），禁止 {@code REQUIRES_NEW}，以便 owner 锁与 harness 写入保持同一物理事务。
 * 回调正常返回只表示当前事务边界已准备好；{@link #afterCommit} 在无外层事务时于本方法提交后执行，加入外层事务时只在 Spring 物理 {@code afterCommit}
 * 时执行。手动压缩等必须离开事务的入口先调用 {@link #assertNoAmbientTransaction}。
 *
 * <p>同一 Store 实例通过 {@link ThreadLocal} 显式拒绝嵌套事务（nested transactions are not supported）， 并在 callback
 * 正常执行完毕后统一触发 {@link PostgresqlHarnessTransaction#rethrowDatabaseFailure()} 实施首个数据库故障 poisoning 校验。
 */
public final class PostgresqlHarnessStore implements HarnessStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Supplier<UUID> idGenerator;
  private final ThreadLocal<Boolean> active = new ThreadLocal<>();
  private final ThreadLocal<List<Runnable>> afterCommitActions = new ThreadLocal<>();

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
    boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
    List<Runnable> pending = new ArrayList<>();
    active.set(Boolean.TRUE);
    afterCommitActions.set(pending);
    T result;
    try {
      result =
          transactions.execute(
              status -> {
                PostgresqlHarnessTransaction transaction =
                    new PostgresqlHarnessTransaction(jdbc, idGenerator);
                try {
                  T value = callback.apply(transaction);
                  transaction.rethrowDatabaseFailure();
                  if (outerTransaction && !pending.isEmpty()) {
                    registerOuterAfterCommit(List.copyOf(pending));
                    pending.clear();
                  }
                  return value;
                } finally {
                  transaction.close();
                }
              });
    } finally {
      afterCommitActions.remove();
      active.remove();
    }
    // 提交成功且 store 边界已释放后才执行：动作可开启新事务读取已提交状态；rollback 时异常已在此前抛出，动作不会执行。
    if (!outerTransaction) {
      for (Runnable action : pending) {
        action.run();
      }
    }
    return result;
  }

  /**
   * 把副作用登记到当前 store callback 对应的物理提交之后。
   *
   * <p>无外层事务时在 {@link #transaction} 成功返回前执行；已加入外层 Spring 事务时注册真实 {@code afterCommit}，rollback 不执行。
   */
  @Override
  public void afterCommit(Runnable action) {
    Objects.requireNonNull(action, "action");
    List<Runnable> pending = afterCommitActions.get();
    if (pending == null) {
      throw new IllegalStateException("afterCommit must be registered inside a store transaction");
    }
    pending.add(action);
  }

  /** 手动压缩 resolve 等入口的事务外守卫：检测到真实 Spring 事务时在工作开始前拒绝，避免慢调用持有 owner / Thread 锁。 */
  @Override
  public void assertNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "manual compaction resolve must run outside an ambient transaction");
    }
  }

  private static void registerOuterAfterCommit(List<Runnable> actions) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            for (Runnable action : actions) {
              action.run();
            }
          }
        });
  }
}
