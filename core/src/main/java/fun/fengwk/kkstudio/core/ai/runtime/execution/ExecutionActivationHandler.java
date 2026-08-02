package fun.fengwk.kkstudio.core.ai.runtime.execution;

/** 分发器为一条已到期激活记录调用的同步处理端口。 */
@FunctionalInterface
public interface ExecutionActivationHandler {

  /**
   * 处理一条无锁快照。
   *
   * @return true 表示已接收并推进持久化状态，false 表示快照已经过期
   */
  boolean handle(ExecutionActivation activation);
}
