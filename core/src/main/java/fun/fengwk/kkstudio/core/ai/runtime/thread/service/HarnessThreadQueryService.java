package fun.fengwk.kkstudio.core.ai.runtime.thread.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

/** Thread 查询：全局列表与完整 chat-runtime 快照。 */
public interface HarnessThreadQueryService {
  HarnessThreadDTO getThread(String threadId);

  /** 全局 Thread keyset page. */
  CursorPageDTO<HarnessThreadDTO> listAll(String sort, String cursor, Integer limit);

  /** Thread keyset page restricted to one Chat's historical associations. */
  CursorPageDTO<HarnessThreadDTO> listByChat(
      long chatId, String sort, String cursor, Integer limit);

  /** Returns the complete chat-runtime projection from one PostgreSQL read snapshot. */
  HarnessThreadSnapshotDTO getSnapshot(String threadId);
}
