package fun.fengwk.kkstudio.core.ai.chat.service;

import java.util.List;

/**
 * Chat 作用域内的 Thread 关联用例。
 *
 * <p>该边界只校验 Chat 并管理 Chat↔Thread 关联/列表 id；不依赖任何 HarnessRuntime DTO/converter。 Thread 存在性与快照校验由 web
 * 层通过 HarnessRuntime 编排。
 */
public interface ChatThreadService {

  /** 在尝试非幂等创建 Thread 之前校验 Chat 存在。 */
  void requireChat(String chatId);

  /** 校验 Chat 并返回其关联的 Thread id 列表，最新关联在前。 */
  List<Long> listThreadIds(String chatId);

  /** 校验 Chat 并幂等地将已有 Thread 与其关联。 */
  void associateThread(String chatId, long threadId);
}
