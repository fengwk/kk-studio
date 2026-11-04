package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.session.Session;

import java.util.List;

/**
 * 命令接受前的窄 preflight 端口：在 store 事务内、幂等重放检查之后、任何 durable command 写入之前被调用（只对全新 batch 调用），返回与入参一一对应、保持
 * {@code idempotencyKey} 与 {@code requestHash} 的最终 command 列表。入参 {@code session} 是已通过事务校验的持久化
 * Session（非裸 UUID），取值与结果一致。仅新建 fork 的 copiedEntries 包含同事务中复制的有效上下文条目，其余情况为空。
 *
 * <p>应用 use-case 用它把瞬时 ATTACHMENT 内容物化为 durable RESOURCE（同一外事务内锁定 READY upload、写入 session blob
 * ref、retain blob、删除已消费 upload）；任何失败向上传播使整个接受事务回滚。实现不得自行开启新事务。
 *
 * <p>它是外部物化端口：Runtime 只以调用参数的形式接收（{@code HarnessRuntime#acceptCommands} 与 {@code
 * HarnessRuntime#spawn}），既不持有实现，也不把它放进 Harness 的请求值对象。
 */
@FunctionalInterface
public interface AcceptancePreflight {

  /** 恒等 preflight：原样返回入参（纯非附件命令路径）。 */
  AcceptancePreflight IDENTITY = (session, commands, copiedEntries) -> commands;

  List<NewThreadCommand> prepare(
      Session session, List<NewThreadCommand> commands, List<Entry> copiedEntries);
}
