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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** MySQL/H2 Session Tree Store；路径读取只按 Entry 主键回溯。 */
@Repository
public class MysqlHarnessSessionStore implements SessionStore, SessionEntryStore {
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public MysqlHarnessSessionStore(
      HarnessSessionMapper sessionMapper, HarnessSessionEntryMapper entryMapper) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
  }

  @Override
  public Optional<Session> find(long sessionId) {
    return Optional.ofNullable(sessionMapper.find(sessionId)).map(this::toSession);
  }

  @Override
  public void create(Session session) {
    if (session.parentSessionId() != null
        || session.rootSessionId() != session.id()
        || session.depth() != 0) {
      throw new IllegalArgumentException(
          "a root session must have neither parent nor non-zero depth");
    }
    sessionMapper.insert(toDO(session));
  }

  @Override
  @Transactional
  public void createFork(Session session, List<SessionEntry> entries) {
    entries = List.copyOf(entries);
    if (session.parentSessionId() == null) {
      throw new IllegalArgumentException("a child session must reference its parent session");
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
    List<SessionEntry> reversePath = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
    Long entryId = leafEntryId;
    while (entryId != null) {
      if (!visited.add(entryId)) {
        throw new InvalidSessionTreeException("cycle detected in session entry path");
      }
      SessionEntry entry = find(sessionId, entryId).orElse(null);
      if (entry == null) {
        throw new InvalidSessionTreeException("missing or cross-session parent entry: " + entryId);
      }
      reversePath.add(entry);
      entryId = entry.parentEntryId();
    }
    Collections.reverse(reversePath);
    return List.copyOf(reversePath);
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
    target.setAgentDefinitionId(session.agentDefinitionId());
    target.setTitle(session.title());
    target.setParentSessionId(session.parentSessionId());
    target.setRootSessionId(session.rootSessionId());
    target.setParentInvocationId(session.parentInvocationId());
    target.setDepth(session.depth());
    target.setVersion(session.version());
    target.setCreateTime(LocalDateTime.ofInstant(session.createdAt(), ZoneOffset.UTC));
    target.setUpdateTime(LocalDateTime.ofInstant(session.updatedAt(), ZoneOffset.UTC));
    return target;
  }

  private HarnessSessionEntryDO toDO(SessionEntry entry) {
    HarnessSessionEntryDO target = new HarnessSessionEntryDO();
    target.setId(entry.id());
    target.setSessionId(entry.sessionId());
    target.setParentEntryId(entry.parentEntryId());
    target.setEntryType(entry.type().value());
    target.setPayloadJson(payloadCodec.encode(entry.payload()));
    target.setCreateTime(LocalDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC));
    return target;
  }

  private Session toSession(HarnessSessionDO row) {
    return new Session(
        row.getId(),
        row.getAgentDefinitionId(),
        row.getTitle(),
        row.getParentSessionId(),
        row.getRootSessionId(),
        row.getParentInvocationId(),
        row.getDepth(),
        row.getVersion(),
        row.getCreateTime().toInstant(ZoneOffset.UTC),
        row.getUpdateTime().toInstant(ZoneOffset.UTC));
  }

  private SessionEntry toEntry(HarnessSessionEntryDO row) {
    SessionEntryType type = SessionEntryType.fromValue(row.getEntryType());
    return new SessionEntry(
        row.getId(),
        row.getSessionId(),
        row.getParentEntryId(),
        type,
        payloadCodec.decode(type, row.getPayloadJson()),
        row.getCreateTime().toInstant(ZoneOffset.UTC));
  }
}
