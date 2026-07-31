package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

/** Chat-scoped Thread association and creation use cases. */
public interface ChatThreadService {

  CursorPageDTO<HarnessThreadDTO> listThreads(
      String chatId, String sort, String cursor, Integer limit);

  /** Creates an UNBOUND Thread and associates it with the Chat in one transaction. */
  HarnessThreadDTO createThread(String chatId);

  /** Idempotently associates an existing global Thread with a Chat. */
  void associateThread(String chatId, String threadId);
}
