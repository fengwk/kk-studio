package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.util.List;

/**
 * 命令接受前的窄 preflight 端口：在 store 事务内、幂等重放检查之后、任何 durable command 写入之前被调用（只对全新 batch 调用），返回与入参一一对应、保持
 * {@code clientCommandId} 与 {@code requestHash} 的最终 command 列表。入参 {@code session} 是已通过事务校验的持久化
 * Session（非裸 UUID），取值与结果一致。
 *
 * <p>应用 use-case 用它把瞬时 ATTACHMENT 内容物化为 durable RESOURCE（同一外事务内锁定 READY upload、写入 session blob
 * ref、retain blob、删除已消费 upload）；任何失败向上传播使整个接受事务回滚。实现不得自行开启新事务。
 */
@FunctionalInterface
public interface AcceptancePreflight {

  /** 恒等 preflight：原样返回入参（纯非附件命令路径）。 */
  AcceptancePreflight IDENTITY = (tx, session, commands) -> commands;

  List<NewThreadCommand> prepare(
      HarnessStore.Transaction tx, Session session, List<NewThreadCommand> commands);
}
