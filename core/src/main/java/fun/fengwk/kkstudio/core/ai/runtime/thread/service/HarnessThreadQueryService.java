package fun.fengwk.kkstudio.core.ai.runtime.thread.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;

import java.util.List;

/** Thread 查询：全局列表与完整 chat-runtime 快照。 */
public interface HarnessThreadQueryService {
  HarnessThreadDTO getThread(String threadId);

  /** 全部用户可见 Thread，按最近更新倒序。 */
  List<HarnessThreadDTO> listAll();

  /** Returns the complete chat-runtime projection from one PostgreSQL read snapshot. */
  HarnessThreadSnapshotDTO getSnapshot(String threadId);
}
