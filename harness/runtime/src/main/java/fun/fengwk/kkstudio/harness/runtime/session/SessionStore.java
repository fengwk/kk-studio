package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Optional;

/** Session 聚合写入端口。append 与 createFork 必须由实现保证原子性。 */
public interface SessionStore {
  Optional<Session> find(String sessionId);

  void create(Session session);

  void createFork(Session session, List<SessionEntry> entries);

  /** 原子写入 Entry 并把 leaf 从 expectedLeafEntryId CAS 到新 Entry。 */
  void append(SessionEntry entry, String expectedLeafEntryId);

  boolean compareAndSetLeaf(String sessionId, String expectedLeafEntryId, String newLeafEntryId);
}
