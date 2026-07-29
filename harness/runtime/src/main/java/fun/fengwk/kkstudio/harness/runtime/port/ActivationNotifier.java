package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;

/**
 * PostgreSQL durable mutation 成功提交后的最佳努力执行信号。
 *
 * <p>调用方只能在相应事务已提交后调用本端口。通知可以重复、乱序或丢失，不能作为任何 durable transition 的前提。
 */
@FunctionalInterface
public interface ActivationNotifier {

  void notifyAfterCommit(ExecutionTarget target);
}
