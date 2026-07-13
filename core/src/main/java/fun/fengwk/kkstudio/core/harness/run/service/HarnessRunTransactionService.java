package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.run.RunTransactions;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Session leaf、activeRunId、完整 Entry 与 Run transition 的事务服务。 */
@Service
public class HarnessRunTransactionService implements RunTransactions {
  private final HarnessRunMapper runMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final RunIdGenerator idGenerator;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public HarnessRunTransactionService(
      HarnessRunMapper runMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      RunIdGenerator idGenerator) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  @Transactional
  public AgentRun submitUserMessage(
      long sessionId, Long expectedLeafEntryId, AgentMessage userMessage, Instant now) {
    Objects.requireNonNull(userMessage, "userMessage");
    if (userMessage.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("submitted message must have USER role");
    }
    HarnessSessionDO session = requireSessionForUpdate(sessionId);
    if (session.getActiveRunId() != null) {
      throw new IllegalStateException("session already has an active run");
    }
    if (!Objects.equals(session.getLeafEntryId(), expectedLeafEntryId)) {
      throw new ConcurrentModificationException("session leaf changed before run submission");
    }

    long runId = idGenerator.newRunId();
    long entryId = idGenerator.newSessionEntryId();
    LocalDateTime timestamp = utc(now);
    entryMapper.insert(
        entry(
            entryId,
            sessionId,
            expectedLeafEntryId,
            runId,
            "message",
            payloadCodec.encode(new MessageEntryPayload(userMessage)),
            timestamp));
    runMapper.insert(queuedRun(runId, sessionId, entryId, timestamp));
    if (sessionMapper.attachRun(sessionId, expectedLeafEntryId, entryId, runId, timestamp) != 1) {
      throw new ConcurrentModificationException("cannot attach active run to session");
    }
    return toRun(runMapper.find(runId));
  }

  @Override
  @Transactional
  public boolean complete(AgentRun claimedRun, MessageEntryPayload assistant, Instant now) {
    validateAssistant(assistant);
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    appendEntry(locked.session(), claimedRun.id(), assistant, now);
    transitionCompleted(claimedRun, RunStatus.SUCCEEDED, now);
    clearActiveRun(claimedRun, now);
    return true;
  }

  @Transactional
  public boolean prepareTools(AgentRun claimedRun, MessageEntryPayload assistant, Instant now) {
    validateAssistant(assistant);
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    appendEntry(locked.session(), claimedRun.id(), assistant, now);
    transitionCompleted(claimedRun, RunStatus.WAITING_TOOLS, now);
    return true;
  }

  @Override
  @Transactional
  public boolean requeue(AgentRun claimedRun, Instant nextAttemptAt, Instant now) {
    if (lockOwned(claimedRun) == null) {
      return false;
    }
    claimedRun.status().requireTransitionTo(RunStatus.QUEUED);
    return runMapper.requeueOwned(
            claimedRun.id(),
            claimedRun.leaseOwner(),
            claimedRun.attempt(),
            utc(nextAttemptAt),
            utc(now))
        == 1;
  }

  @Override
  @Transactional
  public boolean compactAndRequeue(
      AgentRun claimedRun, CompactionEntryPayload compaction, Instant nextAttemptAt, Instant now) {
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (!isAncestor(
        claimedRun.sessionId(), locked.session().getLeafEntryId(), compaction.firstKeptEntryId())) {
      throw new IllegalArgumentException("compaction must retain an entry on the active path");
    }
    appendEntry(locked.session(), claimedRun.id(), compaction, now);
    claimedRun.status().requireTransitionTo(RunStatus.QUEUED);
    if (runMapper.requeueOwned(
            claimedRun.id(),
            claimedRun.leaseOwner(),
            claimedRun.attempt(),
            utc(nextAttemptAt),
            utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during compaction");
    }
    return true;
  }

  @Override
  @Transactional
  public boolean terminate(AgentRun claimedRun, RunStatus terminalStatus, Instant now) {
    if (terminalStatus != RunStatus.FAILED && terminalStatus != RunStatus.CANCELLED) {
      throw new IllegalArgumentException("terminate only accepts FAILED or CANCELLED");
    }
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    claimedRun.status().requireTransitionTo(terminalStatus);
    if (runMapper.terminateOwned(
            claimedRun.id(),
            claimedRun.leaseOwner(),
            claimedRun.attempt(),
            terminalStatus.name(),
            utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during terminal transition");
    }
    clearActiveRun(claimedRun, now);
    return true;
  }

  private LockedRun lockOwned(AgentRun claimedRun) {
    HarnessRunDO run = runMapper.findForUpdate(claimedRun.id());
    if (run == null
        || !RunStatus.RUNNING.name().equals(run.getStatus())
        || !Objects.equals(run.getLeaseOwner(), claimedRun.leaseOwner())
        || run.getAttempt() != claimedRun.attempt()) {
      return null;
    }
    HarnessSessionDO session = requireSessionForUpdate(claimedRun.sessionId());
    if (!Objects.equals(session.getActiveRunId(), claimedRun.id())) {
      throw new IllegalStateException("session active run does not match claimed run");
    }
    return new LockedRun(run, session);
  }

  private void appendEntry(
      HarnessSessionDO session, long runId, SessionEntryPayload payload, Instant now) {
    long entryId = idGenerator.newSessionEntryId();
    LocalDateTime timestamp = utc(now);
    entryMapper.insert(
        entry(
            entryId,
            session.getId(),
            session.getLeafEntryId(),
            runId,
            payload.type().value(),
            payloadCodec.encode(payload),
            timestamp));
    if (sessionMapper.advanceActiveRunLeaf(
            session.getId(), runId, session.getLeafEntryId(), entryId, timestamp)
        != 1) {
      throw new ConcurrentModificationException("session leaf changed during run transition");
    }
    session.setLeafEntryId(entryId);
  }

  private void transitionCompleted(AgentRun claimedRun, RunStatus status, Instant now) {
    claimedRun.status().requireTransitionTo(status);
    if (runMapper.completeTurnOwned(
            claimedRun.id(), claimedRun.leaseOwner(), claimedRun.attempt(), status.name(), utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during turn completion");
    }
  }

  private void clearActiveRun(AgentRun run, Instant now) {
    if (sessionMapper.clearActiveRun(run.sessionId(), run.id(), utc(now)) != 1) {
      throw new ConcurrentModificationException("cannot clear session active run");
    }
  }

  private boolean isAncestor(long sessionId, Long leafEntryId, long ancestorEntryId) {
    Set<Long> visited = new HashSet<>();
    Long current = leafEntryId;
    while (current != null && visited.add(current)) {
      if (current == ancestorEntryId) {
        return true;
      }
      HarnessSessionEntryDO entry = entryMapper.find(sessionId, current);
      if (entry == null) {
        return false;
      }
      current = entry.getParentEntryId();
    }
    return false;
  }

  private HarnessSessionDO requireSessionForUpdate(long sessionId) {
    HarnessSessionDO session = sessionMapper.findForUpdate(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    return session;
  }

  private void validateAssistant(MessageEntryPayload assistant) {
    Objects.requireNonNull(assistant, "assistant");
    if (assistant.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalArgumentException("completed turn entry must have ASSISTANT role");
    }
  }

  private HarnessRunDO queuedRun(
      long runId, long sessionId, long triggerEntryId, LocalDateTime timestamp) {
    HarnessRunDO run = new HarnessRunDO();
    run.setId(runId);
    run.setSessionId(sessionId);
    run.setTriggerEntryId(triggerEntryId);
    run.setStatus(RunStatus.QUEUED.name());
    run.setTurnIndex(0);
    run.setAttempt(0);
    run.setEventSequence(0L);
    run.setNextAttemptAt(timestamp);
    run.setCreateTime(timestamp);
    run.setUpdateTime(timestamp);
    return run;
  }

  private HarnessSessionEntryDO entry(
      long id,
      long sessionId,
      Long parentEntryId,
      long runId,
      String type,
      String payloadJson,
      LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(id);
    entry.setSessionId(sessionId);
    entry.setParentEntryId(parentEntryId);
    entry.setRunId(runId);
    entry.setEntryType(type);
    entry.setPayloadJson(payloadJson);
    entry.setCreateTime(timestamp);
    return entry;
  }

  private AgentRun toRun(HarnessRunDO source) {
    return new AgentRun(
        source.getId(),
        source.getSessionId(),
        source.getTriggerEntryId(),
        RunStatus.valueOf(source.getStatus()),
        source.getTurnIndex(),
        source.getAttempt(),
        source.getEventSequence(),
        source.getLeaseOwner(),
        instant(source.getLeaseUntil()),
        instant(source.getNextAttemptAt()),
        instant(source.getCancelRequestedAt()),
        instant(source.getCreateTime()),
        instant(source.getStartedAt()),
        instant(source.getFinishedAt()),
        instant(source.getUpdateTime()));
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  private record LockedRun(HarnessRunDO run, HarnessSessionDO session) {}
}
