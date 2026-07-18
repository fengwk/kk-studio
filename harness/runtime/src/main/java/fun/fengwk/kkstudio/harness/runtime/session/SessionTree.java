package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Session Tree 的 append 与 fork 领域操作；执行游标属于 AgentThread，不再维护 session leaf。 */
public final class SessionTree {
  private final SessionStore sessionStore;
  private final SessionEntryStore entryStore;
  private final SessionIdGenerator idGenerator;
  private final Clock clock;

  public SessionTree(
      SessionStore sessionStore,
      SessionEntryStore entryStore,
      SessionIdGenerator idGenerator,
      Clock clock) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Session create(Long agentDefinitionId, String title) {
    long sessionId = idGenerator.newSessionId();
    Session session = Session.root(sessionId, agentDefinitionId, title, clock.instant());
    sessionStore.create(session);
    return session;
  }

  /** 在指定 parent 下追加 Entry（parent 即 Thread head 或任意合法父节点）。 */
  public AppendResult append(long sessionId, Long parentEntryId, SessionEntryDraft draft) {
    requireSession(sessionId);
    if (parentEntryId != null) {
      entryStore.loadPath(sessionId, parentEntryId);
    }
    SessionEntry entry =
        new SessionEntry(
            idGenerator.newEntryId(),
            sessionId,
            parentEntryId,
            draft.payload().type(),
            draft.payload(),
            clock.instant());
    sessionStore.append(entry);
    return AppendResult.appended(entry);
  }

  /**
   * 复制源 Session 从根到 fromEntryId 的路径为 child Session；Entry id 不跨 Session 复用。 leaf 不再写在 Session
   * 上——调用方用返回路径的末节点创建 child Thread。
   */
  public ForkResult fork(long sourceSessionId, long fromEntryId) {
    Session source = requireSession(sourceSessionId);
    List<SessionEntry> sourcePath = entryStore.loadPath(sourceSessionId, fromEntryId);
    long targetSessionId = idGenerator.newSessionId();
    List<SessionEntry> clone = clonePath(sourcePath, targetSessionId);
    Session fork =
        new Session(
            targetSessionId,
            source.agentDefinitionId(),
            source.title(),
            source.id(),
            source.rootSessionId(),
            null,
            source.depth() + 1,
            0,
            clock.instant(),
            clock.instant());
    sessionStore.createFork(fork, clone);
    long headEntryId = clone.get(clone.size() - 1).id();
    return new ForkResult(fork, headEntryId, clone);
  }

  public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
    if (parentEntryId != null) {
      entryStore.loadPath(sessionId, parentEntryId);
    }
    return entryStore.listChildren(sessionId, parentEntryId);
  }

  private List<SessionEntry> clonePath(List<SessionEntry> path, long targetSessionId) {
    Map<Long, Long> ids = new HashMap<>();
    List<SessionEntry> clone = new ArrayList<>(path.size());
    for (SessionEntry source : path) {
      long cloneId = idGenerator.newEntryId();
      Long parentId = source.parentEntryId() == null ? null : ids.get(source.parentEntryId());
      if (source.parentEntryId() != null && parentId == null) {
        throw new InvalidSessionTreeException("source path is not ordered from root to leaf");
      }
      clone.add(
          new SessionEntry(
              cloneId,
              targetSessionId,
              parentId,
              source.type(),
              source.payload(),
              clock.instant()));
      ids.put(source.id(), cloneId);
    }
    return List.copyOf(clone);
  }

  private Session requireSession(long sessionId) {
    return sessionStore
        .find(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
  }

  public record ForkResult(Session session, long headEntryId, List<SessionEntry> entries) {
    public ForkResult {
      session = Objects.requireNonNull(session, "session");
      if (headEntryId <= 0) {
        throw new IllegalArgumentException("headEntryId must be positive");
      }
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
  }
}
