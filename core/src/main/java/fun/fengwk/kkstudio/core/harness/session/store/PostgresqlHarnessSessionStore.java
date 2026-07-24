package fun.fengwk.kkstudio.core.harness.session.store;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.harness.runtime.session.InvalidSessionTreeException;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.SessionStore;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL Session/Entry store on final schema {@code harness_session}/{@code harness_entry}.
 *
 * <p>{@code root_session_id}/{@code depth}/{@code version} 不落库：读取时由 parent 链派生；写入只校验 runtime {@link
 * Session} 不变量并投影 final 列。
 */
@Repository
public class PostgresqlHarnessSessionStore implements SessionStore, SessionEntryStore {
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public PostgresqlHarnessSessionStore(
      HarnessSessionMapper sessionMapper, HarnessSessionEntryMapper entryMapper) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
  }

  @Override
  public Optional<Session> find(long sessionId) {
    HarnessSessionDO row = sessionMapper.find(sessionId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(toSession(row));
  }

  @Override
  public void create(Session session) {
    if (session.parentSessionId() != null
        || session.rootSessionId() != session.id()
        || session.depth() != 0
        || session.parentInvocationId() != null) {
      throw new IllegalArgumentException(
          "a root session must have neither parent nor non-zero depth");
    }
    sessionMapper.insert(toDO(session));
  }

  @Override
  @Transactional
  public void createFork(Session session, List<SessionEntry> entries) {
    entries = List.copyOf(entries);
    if (session.parentSessionId() == null || session.parentInvocationId() == null) {
      throw new IllegalArgumentException(
          "a child session must reference parent session and parent invocation together");
    }
    Session parent = requireSession(session.parentSessionId());
    if (parent.rootSessionId() != session.rootSessionId()
        || parent.depth() + 1 != session.depth()) {
      throw new InvalidSessionTreeException("child session hierarchy is inconsistent");
    }
    validateFork(entries, session.id());
    sessionMapper.insert(toDO(session));
    for (SessionEntry entry : entries) {
      entryMapper.insert(toDO(entry));
    }
  }

  @Override
  @Transactional
  public void append(SessionEntry entry) {
    requireSession(entry.sessionId());
    if (entry.parentEntryId() != null
        && entryMapper.find(entry.sessionId(), entry.parentEntryId()) == null) {
      throw new InvalidSessionTreeException("entry parent does not belong to session");
    }
    entryMapper.insert(toDO(entry));
  }

  @Override
  public Optional<SessionEntry> find(long sessionId, long entryId) {
    return Optional.ofNullable(entryMapper.find(sessionId, entryId)).map(this::toEntry);
  }

  @Override
  public List<SessionEntry> loadPath(long sessionId, long leafEntryId) {
    List<HarnessSessionEntryDO> rows = entryMapper.loadPath(sessionId, leafEntryId);
    if (rows.isEmpty()) {
      throw new InvalidSessionTreeException(
          "missing or cross-session parent entry: " + leafEntryId);
    }
    Set<Long> visited = new HashSet<>();
    for (HarnessSessionEntryDO row : rows) {
      if (!visited.add(row.getId())) {
        throw new InvalidSessionTreeException("cycle detected in session entry path");
      }
      if (row.getSessionId() == null || row.getSessionId() != sessionId) {
        throw new InvalidSessionTreeException(
            "missing or cross-session parent entry: " + row.getId());
      }
    }
    return rows.stream().map(this::toEntry).toList();
  }

  @Override
  public List<SessionEntry> listChildren(long sessionId, Long parentEntryId) {
    return entryMapper.listChildren(sessionId, parentEntryId).stream().map(this::toEntry).toList();
  }

  private Session requireSession(long sessionId) {
    return find(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
  }

  private void validateFork(List<SessionEntry> entries, long sessionId) {
    if (entries.isEmpty()) {
      throw new InvalidSessionTreeException("fork path must not be empty");
    }
    Long expectedParent = null;
    for (SessionEntry entry : entries) {
      if (entry.sessionId() != sessionId
          || !Objects.equals(entry.parentEntryId(), expectedParent)) {
        throw new InvalidSessionTreeException(
            "fork entries must be a root-to-leaf chain in the target session");
      }
      expectedParent = entry.id();
    }
  }

  private HarnessSessionDO toDO(Session session) {
    HarnessSessionDO target = new HarnessSessionDO();
    target.setId(session.id());
    target.setTitle(session.title());
    target.setMainThreadId(session.mainThreadId());
    target.setParentSessionId(session.parentSessionId());
    target.setParentInvocationId(session.parentInvocationId());
    target.setCreatedAt(OffsetDateTime.ofInstant(session.createdAt(), ZoneOffset.UTC));
    target.setUpdatedAt(OffsetDateTime.ofInstant(session.updatedAt(), ZoneOffset.UTC));
    return target;
  }

  private HarnessSessionEntryDO toDO(SessionEntry entry) {
    HarnessSessionEntryDO target = new HarnessSessionEntryDO();
    target.setId(entry.id());
    target.setSessionId(entry.sessionId());
    target.setParentEntryId(entry.parentEntryId());
    // final schema stores uppercase EntryType-compatible names when available.
    target.setEntryType(toPersistedEntryType(entry.type()));
    target.setPayloadJson(payloadCodec.encode(entry.payload()));
    target.setCreatedAt(OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC));
    return target;
  }

  private Session toSession(HarnessSessionDO row) {
    long id = row.getId();
    Long rootId = sessionMapper.findRootSessionId(id);
    int depth = sessionMapper.findDepth(id);
    long resolvedRoot = rootId == null ? id : rootId;
    return new Session(
        id,
        row.getMainThreadId(),
        row.getTitle(),
        row.getParentSessionId(),
        resolvedRoot,
        row.getParentInvocationId(),
        depth,
        0L,
        row.getCreatedAt().toInstant(),
        row.getUpdatedAt().toInstant());
  }

  private SessionEntry toEntry(HarnessSessionEntryDO row) {
    SessionEntryType type = fromPersistedEntryType(row.getEntryType());
    return new SessionEntry(
        row.getId(),
        row.getSessionId(),
        row.getParentEntryId(),
        type,
        payloadCodec.decode(type, row.getPayloadJson()),
        row.getCreatedAt().toInstant());
  }

  private static String toPersistedEntryType(SessionEntryType type) {
    return switch (type) {
      case ROOT -> "ROOT";
      case MESSAGE -> "MESSAGE";
      case CUSTOM_MESSAGE -> "CUSTOM_MESSAGE";
      case COMPACTION -> "COMPACTION";
      case ASSISTANT_ERROR -> "ASSISTANT_ERROR";
      case LABEL -> "LABEL";
      case BRANCH_SUMMARY -> "BRANCH_SUMMARY";
        // Legacy runtime types are not part of final schema check constraint.
      case AGENT_CHANGE, MODEL_CHANGE, CUSTOM -> type.name();
    };
  }

  private static SessionEntryType fromPersistedEntryType(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("entry type must not be blank");
    }
    String normalized = value.trim();
    try {
      return SessionEntryType.valueOf(normalized.toUpperCase());
    } catch (IllegalArgumentException ignored) {
      return SessionEntryType.fromValue(normalized.toLowerCase());
    }
  }
}
