package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Optional;

/** Session 聚合写入端口。createFork 与 append 必须由实现保证原子性。 */
public interface SessionStore {
  Optional<Session> find(long sessionId);

  void create(Session session);

  void createFork(Session session, List<SessionEntry> entries);

  /** 原子写入 Entry；校验 parent 属于同一 session，不做 leaf CAS。 */
  void append(SessionEntry entry);
}
