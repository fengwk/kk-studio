package fun.fengwk.kkstudio.core.ai.chat.service;

import java.util.List;

/**
 * Chat-scoped Thread association use cases.
 *
 * <p>This boundary only validates the Chat and manages Chat↔Thread associations/list ids; it has no
 * HarnessRuntime DTO/converter dependency. Thread existence and snapshot validation is orchestrated
 * by the web layer through HarnessRuntime.
 */
public interface ChatThreadService {

  /** Validates that the Chat exists before a non-idempotent Thread create is attempted. */
  void requireChat(String chatId);

  /** Validates the Chat and returns its associated Thread ids ordered newest association first. */
  List<Long> listThreadIds(String chatId);

  /** Validates the Chat and idempotently associates an existing Thread with it. */
  void associateThread(String chatId, long threadId);
}
