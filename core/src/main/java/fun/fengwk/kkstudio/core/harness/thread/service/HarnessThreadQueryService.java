package fun.fengwk.kkstudio.core.harness.thread.service;

import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadSnapshotDTO;

import java.util.List;

/** Thread 查询：全局/Session 列表、路径 entries、inputs。 */
public interface HarnessThreadQueryService {
  HarnessThreadDTO getThread(String threadId);

  /** 全部用户可见 Thread，按最近更新倒序。 */
  List<HarnessThreadDTO> listAll();

  List<HarnessSessionEntryDTO> listPathEntries(String threadId);

  List<HarnessThreadInputDTO> listInputs(String threadId);

  /** Returns the complete chat-runtime projection from one PostgreSQL read snapshot. */
  HarnessThreadSnapshotDTO getSnapshot(String threadId);
}
