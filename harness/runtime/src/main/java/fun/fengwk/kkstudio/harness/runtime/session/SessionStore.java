package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Optional;

/** Session 聚合写入端口。append 与 createFork 必须由实现保证原子性。 */
public interface SessionStore {
  Optional<Session> find(long sessionId);

  void create(Session session);

  void createFork(Session session, List<SessionEntry> entries);

  /** 原子写入 Entry 并以 leaf 与 version 双重 CAS 更新 Session。 */
  void append(SessionEntry entry, Long expectedLeafEntryId, long expectedSessionVersion);

  boolean compareAndSetLeaf(
      long sessionId, Long expectedLeafEntryId, long expectedSessionVersion, Long newLeafEntryId);
}
