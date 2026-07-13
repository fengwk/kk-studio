package fun.fengwk.kkstudio.harness.runtime.session;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Session 的单 leaf 导航、CAS append 和 fork 领域操作。 */
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

  public void create(String sessionId, long workspaceId) {
    sessionStore.create(new Session(sessionId, workspaceId, null, null, clock.instant()));
  }

  public AppendResult append(
      String sessionId, String expectedLeafEntryId, SessionEntryDraft draft) {
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
            draft.payload().type(),
            draft.payload(),
            clock.instant());
    try {
      sessionStore.append(entry, expectedLeafEntryId);
      return AppendResult.appended(entry);
    } catch (SessionLeafConflictException exception) {
      return AppendResult.conflict();
    }
  }

  /** 仅允许将 leaf 退回当前活动链的祖先（或空根）。 */
  public boolean navigate(String sessionId, String expectedLeafEntryId, String ancestorEntryId) {
    Session session = requireSession(sessionId);
    if (!Objects.equals(session.leafEntryId(), expectedLeafEntryId)) {
      return false;
    }
    if (ancestorEntryId != null) {
      if (expectedLeafEntryId == null) {
        throw new InvalidSessionTreeException("an empty session has no ancestor entry");
      }
      boolean ancestor =
          entryStore.loadPath(sessionId, expectedLeafEntryId).stream()
              .anyMatch(entry -> entry.entryId().equals(ancestorEntryId));
      if (!ancestor) {
        throw new InvalidSessionTreeException("target leaf is not an ancestor of the active leaf");
      }
    }
    return sessionStore.compareAndSetLeaf(sessionId, expectedLeafEntryId, ancestorEntryId);
  }

  /** 将源 Session 的当前路径复制为新 Session，Entry id 不跨 Session 复用。 */
  public Session fork(String sourceSessionId, String expectedLeafEntryId) {
    Session source = requireSession(sourceSessionId);
    if (!Objects.equals(source.leafEntryId(), expectedLeafEntryId)) {
      throw new SessionLeafConflictException(sourceSessionId, expectedLeafEntryId);
    }
    List<SessionEntry> sourcePath =
        expectedLeafEntryId == null ? List.of() : entryStore.loadPath(sourceSessionId, expectedLeafEntryId);
    String targetSessionId = idGenerator.newSessionId();
    List<SessionEntry> clone = clonePath(sourcePath, targetSessionId);
    String targetLeaf = clone.isEmpty() ? null : clone.get(clone.size() - 1).entryId();
    Session fork =
        new Session(targetSessionId, source.workspaceId(), source.sessionId(), targetLeaf, clock.instant());
    sessionStore.createFork(fork, clone);
    return fork;
  }

  private List<SessionEntry> clonePath(List<SessionEntry> path, String targetSessionId) {
    Map<String, String> ids = new HashMap<>();
    List<SessionEntry> clone = new ArrayList<>(path.size());
    for (SessionEntry source : path) {
      String cloneId = idGenerator.newEntryId();
      String parentId = source.parentEntryId() == null ? null : ids.get(source.parentEntryId());
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
              Instant.now(clock)));
      ids.put(source.entryId(), cloneId);
    }
    return List.copyOf(clone);
  }

  private Session requireSession(String sessionId) {
    return sessionStore
        .find(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
  }
}
