package fun.fengwk.kkstudio.platform.plugin.testing;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 测试专用的无操作事务管理器：复用 Spring 的标准事务生命周期（含同步回调注册与 afterCommit 触发），但不做任何真实资源管理。
 *
 * <p>它让「提交后唤醒」这类事务同步语义在纯内存测试里与真实数据库事务一致，同时不引入数据源。
 */
public class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

  @Override
  protected Object doGetTransaction() {
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {}

  @Override
  protected void doCommit(DefaultTransactionStatus status) {}

  @Override
  protected void doRollback(DefaultTransactionStatus status) {}
}
