package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Session 的单 leaf checkout、CAS append 和 fork 领域操作。 */
public final class SessionTree {
  private final SessionStore sessionStore;
  private final SessionEntryStore entryStore;
  private final SessionIdGenerator idGenerator;
  private final SessionYoloResolver yoloResolver;
  private final Clock clock;

  public SessionTree(
      SessionStore sessionStore,
      SessionEntryStore entryStore,
      SessionIdGenerator idGenerator,
      SessionYoloResolver yoloResolver,
      Clock clock) {
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.yoloResolver = Objects.requireNonNull(yoloResolver, "yoloResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Session create(long workspaceId, Long agentDefinitionId, String title) {
    long sessionId = idGenerator.newSessionId();
    Session session =
        Session.root(
            sessionId,
            workspaceId,
            agentDefinitionId,
            title,
            yoloResolver.defaultYolo(workspaceId),
            clock.instant());
    sessionStore.create(session);
    return session;
  }

  public AppendResult append(long sessionId, Long expectedLeafEntryId, SessionEntryDraft draft) {
    Session session = requireSession(sessionId);
    if (!Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
      return AppendResult.conflict();
    }
    if (expectedLeafEntryId != null) {
      entryStore.loadPath(sessionId, expectedLeafEntryId);
    }
    SessionEntry entry =
        new SessionEntry(
            idGenerator.newEntryId(),
            sessionId,
            expectedLeafEntryId,
            draft.runId(),
            draft.payload().type(),
            draft.payload(),
            clock.instant());
    try {
      sessionStore.append(entry, expectedLeafEntryId, session.version());
      return AppendResult.appended(entry);
    } catch (SessionLeafConflictException exception) {
      return AppendResult.conflict();
    }
  }

  /** 将 active leaf 切换到任一有效的同 Session Entry；active-run 约束由上层执行。 */
  public boolean checkout(long sessionId, Long expectedLeafEntryId, long targetEntryId) {
    Session session = requireSession(sessionId);
    if (!Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
      return false;
    }
    entryStore.loadPath(sessionId, targetEntryId);
    return sessionStore.compareAndSetLeaf(
        sessionId, expectedLeafEntryId, session.version(), targetEntryId);
  }

  /** 复制源 Session 的当前路径为 child Session，Entry id 不跨 Session 复用。 */
  public Session fork(long sourceSessionId, Long expectedLeafEntryId) {
    Session source = requireSession(sourceSessionId);
    if (!Objects.equals(source.leafEntryId(), expectedLeafEntryId)) {
      throw new SessionLeafConflictException(sourceSessionId, expectedLeafEntryId);
    }
    List<SessionEntry> sourcePath =
        expectedLeafEntryId == null
            ? List.of()
            : entryStore.loadPath(sourceSessionId, expectedLeafEntryId);
    long targetSessionId = idGenerator.newSessionId();
    List<SessionEntry> clone = clonePath(sourcePath, targetSessionId);
    Long targetLeaf = clone.isEmpty() ? null : clone.get(clone.size() - 1).id();
    Session fork =
        new Session(
            targetSessionId,
            source.workspaceId(),
            source.agentDefinitionId(),
            source.title(),
            targetLeaf,
            null,
            source.id(),
            source.rootSessionId(),
            null,
            source.depth() + 1,
            false,
            0,
            clock.instant(),
            clock.instant());
    sessionStore.createFork(fork, clone);
    return fork;
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
              null,
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
}
