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
import fun.fengwk.kkstudio.harness.runtime.session.SessionLeafConflictException;
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

/** MySQL/H2 的新语义 Session Tree Store；路径读取只按 Entry 主键回溯，不读取 Run Event。 */
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
        || session.leafEntryId() != null
        || session.rootSessionId() != session.id()
        || session.depth() != 0) {
      throw new IllegalArgumentException("a root session must have neither parent nor leaf");
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
    validateFork(session, entries);
    sessionMapper.insert(toDO(session));
    for (SessionEntry entry : entries) {
      entryMapper.insert(toDO(entry));
    }
  }

  @Override
  @Transactional
  public void append(SessionEntry entry, Long expectedLeafEntryId, long expectedSessionVersion) {
    Session current = requireSession(entry.sessionId());
    if (current.version() != expectedSessionVersion) {
      throw new SessionLeafConflictException(entry.sessionId(), expectedLeafEntryId);
    }
    if (!Objects.equals(entry.parentEntryId(), expectedLeafEntryId)) {
      throw new InvalidSessionTreeException("entry parent must equal the expected leaf");
    }
    if (expectedLeafEntryId != null
        && entryMapper.find(entry.sessionId(), expectedLeafEntryId) == null) {
      throw new InvalidSessionTreeException("entry parent does not belong to session");
    }
    entryMapper.insert(toDO(entry));
    if (sessionMapper.compareAndSetLeaf(
            entry.sessionId(),
            expectedLeafEntryId,
            expectedSessionVersion,
            entry.id(),
            LocalDateTime.now(ZoneOffset.UTC))
        != 1) {
      throw new SessionLeafConflictException(entry.sessionId(), expectedLeafEntryId);
    }
  }

  @Override
  public boolean compareAndSetLeaf(
      long sessionId, Long expectedLeafEntryId, long expectedSessionVersion, Long newLeafEntryId) {
    Session current = requireSession(sessionId);
    if (current.version() != expectedSessionVersion) {
      return false;
    }
    if (newLeafEntryId != null && entryMapper.find(sessionId, newLeafEntryId) == null) {
      throw new InvalidSessionTreeException("new leaf does not belong to session");
    }
    return sessionMapper.compareAndSetLeaf(
            sessionId,
            expectedLeafEntryId,
            expectedSessionVersion,
            newLeafEntryId,
            LocalDateTime.now(ZoneOffset.UTC))
        == 1;
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

  private void validateFork(Session session, List<SessionEntry> entries) {
    Long expectedParent = null;
    for (SessionEntry entry : entries) {
      if (entry.sessionId() != session.id()
          || !Objects.equals(entry.parentEntryId(), expectedParent)) {
        throw new InvalidSessionTreeException(
            "fork entries must be a root-to-leaf chain in the target session");
      }
      expectedParent = entry.id();
    }
    if (!Objects.equals(session.leafEntryId(), expectedParent)) {
      throw new InvalidSessionTreeException("child session leaf does not match cloned path");
    }
  }

  private HarnessSessionDO toDO(Session session) {
    HarnessSessionDO target = new HarnessSessionDO();
    target.setId(session.id());
    target.setAgentDefinitionId(session.agentDefinitionId());
    target.setTitle(session.title());
    target.setLeafEntryId(session.leafEntryId());
    target.setActiveRunId(session.activeRunId());
    target.setParentSessionId(session.parentSessionId());
    target.setRootSessionId(session.rootSessionId());
    target.setParentInvocationId(session.parentInvocationId());
    target.setDepth(session.depth());
    target.setYoloEnabled(session.yoloEnabled());
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
    target.setRunId(entry.runId());
    target.setEntryType(entry.type().value());
    target.setPayloadJson(payloadCodec.encode(entry.payload()));
    target.setCreateTime(LocalDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC));
    return target;
  }

  private Session toSession(HarnessSessionDO source) {
    return new Session(
        source.getId(),
        source.getAgentDefinitionId(),
        source.getTitle(),
        source.getLeafEntryId(),
        source.getActiveRunId(),
        source.getParentSessionId(),
        source.getRootSessionId(),
        source.getParentInvocationId(),
        source.getDepth(),
        source.getYoloEnabled(),
        source.getVersion(),
        source.getCreateTime().toInstant(ZoneOffset.UTC),
        source.getUpdateTime().toInstant(ZoneOffset.UTC));
  }

  private SessionEntry toEntry(HarnessSessionEntryDO source) {
    SessionEntryType type = SessionEntryType.fromValue(source.getEntryType());
    return new SessionEntry(
        source.getId(),
        source.getSessionId(),
        source.getParentEntryId(),
        source.getRunId(),
        type,
        payloadCodec.decode(type, source.getPayloadJson()),
        source.getCreateTime().toInstant(ZoneOffset.UTC));
  }
}
