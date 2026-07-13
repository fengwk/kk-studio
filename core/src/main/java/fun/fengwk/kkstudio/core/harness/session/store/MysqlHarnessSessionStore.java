package fun.fengwk.kkstudio.core.harness.session.store;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL/H2 的新语义 Session Tree Store；路径读取只按 entry id 回溯，不读取 Run Event。 */
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
  public Optional<Session> find(String sessionId) {
    return Optional.ofNullable(sessionMapper.find(sessionId)).map(this::toSession);
  }

  @Override
  public void create(Session session) {
    if (session.parentSessionId() != null || session.leafEntryId() != null) {
      throw new IllegalArgumentException("a newly created session must have neither parent nor leaf");
    }
    sessionMapper.insert(toDO(session));
  }

  @Override
  @Transactional
  public void createFork(Session session, List<SessionEntry> entries) {
    entries = List.copyOf(entries);
    if (session.parentSessionId() == null) {
      throw new IllegalArgumentException("a fork must reference its source session");
    }
    Session parent = requireSession(session.parentSessionId());
    if (parent.workspaceId() != session.workspaceId()) {
      throw new InvalidSessionTreeException("a fork cannot cross workspaces");
    }
    validateFork(session, entries);
    sessionMapper.insert(toDO(session));
    for (SessionEntry entry : entries) {
      entryMapper.insert(toDO(entry));
    }
  }

  @Override
  @Transactional
  public void append(SessionEntry entry, String expectedLeafEntryId) {
    if (!entry.sessionId().equals(requireSession(entry.sessionId()).sessionId())) {
      throw new IllegalArgumentException("entry session does not exist");
    }
    if (!Objects.equals(entry.parentEntryId(), expectedLeafEntryId)) {
      throw new InvalidSessionTreeException("entry parent must equal the expected leaf");
    }
    if (expectedLeafEntryId != null && entryMapper.find(entry.sessionId(), expectedLeafEntryId) == null) {
      throw new InvalidSessionTreeException("entry parent does not belong to session");
    }
    entryMapper.insert(toDO(entry));
    if (sessionMapper.compareAndSetLeaf(
            entry.sessionId(), expectedLeafEntryId, entry.entryId(), LocalDateTime.now(ZoneOffset.UTC))
        != 1) {
      throw new SessionLeafConflictException(entry.sessionId(), expectedLeafEntryId);
    }
  }

  @Override
  public boolean compareAndSetLeaf(String sessionId, String expectedLeafEntryId, String newLeafEntryId) {
    requireSession(sessionId);
    if (newLeafEntryId != null && entryMapper.find(sessionId, newLeafEntryId) == null) {
      throw new InvalidSessionTreeException("new leaf does not belong to session");
    }
    return sessionMapper.compareAndSetLeaf(
            sessionId, expectedLeafEntryId, newLeafEntryId, LocalDateTime.now(ZoneOffset.UTC))
        == 1;
  }

  @Override
  public Optional<SessionEntry> find(String sessionId, String entryId) {
    return Optional.ofNullable(entryMapper.find(sessionId, entryId)).map(this::toEntry);
  }

  @Override
  public List<SessionEntry> loadPath(String sessionId, String leafEntryId) {
    if (leafEntryId == null || leafEntryId.isBlank()) {
      throw new IllegalArgumentException("leafEntryId must not be blank");
    }
    List<SessionEntry> reversePath = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    String entryId = leafEntryId;
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

  private Session requireSession(String sessionId) {
    return find(sessionId).orElseThrow(() -> new IllegalArgumentException("unknown session: " + sessionId));
  }

  private void validateFork(Session session, List<SessionEntry> entries) {
    String expectedParent = null;
    for (SessionEntry entry : entries) {
      if (!entry.sessionId().equals(session.sessionId())
          || !Objects.equals(entry.parentEntryId(), expectedParent)) {
        throw new InvalidSessionTreeException("fork entries must be a root-to-leaf chain in the target session");
      }
      expectedParent = entry.entryId();
    }
    if (!Objects.equals(session.leafEntryId(), expectedParent)) {
      throw new InvalidSessionTreeException("fork session leaf does not match cloned path");
    }
  }

  private HarnessSessionDO toDO(Session session) {
    HarnessSessionDO target = new HarnessSessionDO();
    target.setId(AgentIdGenerator.nextHarnessSessionId());
    target.setSessionId(session.sessionId());
    target.setWorkspaceId(session.workspaceId());
    target.setParentSessionId(session.parentSessionId());
    target.setLeafEntryId(session.leafEntryId());
    target.setCreateTime(LocalDateTime.ofInstant(session.createdAt(), ZoneOffset.UTC));
    return target;
  }

  private HarnessSessionEntryDO toDO(SessionEntry entry) {
    HarnessSessionEntryDO target = new HarnessSessionEntryDO();
    target.setId(AgentIdGenerator.nextHarnessSessionEntryId());
    target.setEntryId(entry.entryId());
    target.setSessionId(entry.sessionId());
    target.setParentEntryId(entry.parentEntryId());
    target.setEntryType(entry.type().value());
    target.setPayloadJson(payloadCodec.encode(entry.payload()));
    target.setCreateTime(LocalDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC));
    return target;
  }

  private Session toSession(HarnessSessionDO source) {
    return new Session(
        source.getSessionId(),
        source.getWorkspaceId(),
        source.getParentSessionId(),
        source.getLeafEntryId(),
        source.getCreateTime().toInstant(ZoneOffset.UTC));
  }

  private SessionEntry toEntry(HarnessSessionEntryDO source) {
    SessionEntryType type = SessionEntryType.fromValue(source.getEntryType());
    return new SessionEntry(
        source.getEntryId(),
        source.getSessionId(),
        source.getParentEntryId(),
        type,
        payloadCodec.decode(type, source.getPayloadJson()),
        source.getCreateTime().toInstant(ZoneOffset.UTC));
  }
}
