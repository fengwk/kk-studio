package fun.fengwk.kkstudio.harness.spi.execution;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;

/**
 * 数据库登记回调：只在 PREPARED 已持久化之后执行，返回事务已提交成功的登记事实。
 *
 * <p>回调内不得进入 body 或发出外部请求；事务结果只有在回调正常返回后才是确定的。事务提交成功但没有登记（stale、Stop 或已失权）时返回 {@code
 * null}：这是正常失权结果，不是异常。抛出异常表示未登记或结果不明，端口不会允许 body 启动，并在释放已取得资源后按精确身份对账。
 */
@FunctionalInterface
public interface LocalExecutionRegistration {

  /**
   * 登记本次执行并返回已提交的事实；事务提交成功但确定没有登记时返回 {@code null}。
   *
   * <p>不得返回与冻结身份不符的事实。
   */
  ExecutionExitFact register();
}
