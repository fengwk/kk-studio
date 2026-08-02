package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

/** Chat-scoped Thread association and creation use cases. */
public interface ChatThreadService {

  CursorPageDTO<HarnessThreadDTO> listThreads(
      String chatId, String sort, String cursor, Integer limit);

  /** Atomically creates and associates a Thread from the Chat defaults. */
  HarnessThreadDTO createThread(String chatId);

  /** Idempotently associates an existing global Thread with a Chat. */
  void associateThread(String chatId, String threadId);
}
