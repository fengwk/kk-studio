package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

/** Chat-scoped Thread association and creation use cases. */
public interface ChatThreadService {

  CursorPageDTO<HarnessThreadDTO> listThreads(
      String chatId, String sort, String cursor, Integer limit);

  /** Atomically creates and associates a Thread for the Chat. */
  HarnessThreadDTO createThread(String chatId, HarnessThreadCreateDTO dto);

  /** Idempotently associates an existing global Thread with a Chat. */
  void associateThread(String chatId, String threadId);
}
