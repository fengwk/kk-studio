package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;

import java.util.List;

/**
 * Chat 作用域 Thread 的应用 use-case 事务边界。
 *
 * <p>createChatThread：校验 Chat 存在，在单一应用事务内原子创建 Session/ROOT/Thread 并关联到 Chat。
 *
 * <p>submitCommands：在单一应用事务内原子入队命令——幂等 hash 重放优先（任何 upload 消费之前），新命令把瞬时 ATTACHMENT(uploadId) 物化为
 * durable RESOURCE（锁定 READY upload、权威文件名、session blob ref + retain、 删除已消费
 * upload）后入队；任何一步失败整体回滚。事务加入外层 Spring 事务（store PROPAGATION_REQUIRED、blob 计数 MANDATORY）。
 */
public interface ChatThreadCommandService {

  /** 应用事务：校验 Chat 存在并原子创建 + 关联 Thread。 */
  CreatedThread createChatThread(String chatId, CreateThreadCommand command);

  /** 应用事务：原子入队命令 batch（含附件消费）。 */
  List<ThreadCommand> submitCommands(ThreadCommandBatch batch);
}
