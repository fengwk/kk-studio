package fun.fengwk.kkstudio.core.harness.control.service;

import static java.util.Objects.requireNonNull;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.ControlPolicy;
import fun.fengwk.kkstudio.harness.runtime.control.ControlPolicyCodec;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化 steer/follow-up 命令，并在无 active Run 时将 follow-up 原子提升为新 Run。 */
@Service
public class HarnessRunControlCommandService {

  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper sessionEntryMapper;
  private final HarnessRunMapper runMapper;
  private final RunControlMessageStore controlStore;
  private final RunControlIdGenerator controlIdGenerator;
  private final HarnessRunTransactionService runTransactions;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public HarnessRunControlCommandService(
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper sessionEntryMapper,
      HarnessRunMapper runMapper,
      RunControlMessageStore controlStore,
      RunControlIdGenerator controlIdGenerator,
      HarnessRunTransactionService runTransactions) {
    this.sessionMapper = requireNonNull(sessionMapper, "sessionMapper");
    this.sessionEntryMapper = requireNonNull(sessionEntryMapper, "sessionEntryMapper");
    this.runMapper = requireNonNull(runMapper, "runMapper");
    this.controlStore = requireNonNull(controlStore, "controlStore");
    this.controlIdGenerator = requireNonNull(controlIdGenerator, "controlIdGenerator");
    this.runTransactions = requireNonNull(runTransactions, "runTransactions");
  }

  /** 接受完整 USER 消息；返回 PENDING 或已直接提升的 PROMOTED 快照。 */
  @Transactional
  public RunControlMessage submit(
      long sessionId, RunControlKind kind, AgentMessage userMessage, Instant now) {
    requireNonNull(kind, "kind");
    requireNonNull(userMessage, "userMessage");
    requireNonNull(now, "now");
    if (userMessage.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("control message must have USER role");
    }

    HarnessSessionDO hint = sessionMapper.find(sessionId);
    if (hint == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    Long activeHint = hint.getActiveRunId();

    if (activeHint == null) {
      return submitNoActive(sessionId, kind, userMessage, now);
    }

    HarnessRunDO lockedRun = runMapper.findForUpdate(activeHint);
    HarnessSessionDO lockedSession =
        requireNonNull(sessionMapper.findForUpdate(sessionId), "session disappeared while locking");
    if (lockedRun == null || isTerminal(lockedRun)) {
      return submitAfterActiveRunEnded(
          lockedSession, activeHint, kind, userMessage, now, "active run is unavailable");
    }
    if (lockedRun.getCancelRequestedAt() != null) {
      throw new RunControlConflictException(
          kind,
          "active run cancel requested, cannot accept "
              + kind
              + " for session "
              + sessionId
              + " run "
              + activeHint);
    }
    if (!activeHint.equals(lockedSession.getActiveRunId())) {
      return submitAfterActiveRunEnded(
          lockedSession, activeHint, kind, userMessage, now, "session active run changed");
    }

    ControlPolicy policy = freezePolicy(lockedSession);
    ControlConsumptionMode mode = policy.modeFor(kind);
    long controlId = controlIdGenerator.newControlMessageId();
    RunControlMessage pending =
        new RunControlMessage(
            controlId,
            sessionId,
            activeHint,
            kind,
            mode,
            userMessage,
            RunControlStatus.PENDING,
            null,
            null,
            now,
            null);
    if (controlStore.insert(pending) != 1) {
      throw new IllegalStateException("cannot insert control message: " + controlId);
    }

    RunEventDraft requested =
        new RunEventDraft(
            requestedEventType(kind),
            RunEventPayloads.of(
                "controlId", controlId, "kind", kind.name(), "consumptionMode", mode.name()));
    runTransactions.appendExternalEvents(activeHint, List.of(requested), now);

    return pending;
  }

  private RunControlMessage submitNoActive(
      long sessionId, RunControlKind kind, AgentMessage userMessage, Instant now) {
    HarnessSessionDO session =
        requireNonNull(sessionMapper.findForUpdate(sessionId), "session disappeared while locking");
    Long currentActive = session.getActiveRunId();
    if (currentActive != null) {
      throw new RunControlConflictException(
          kind,
          "session active run changed under lock, cannot accept "
              + kind
              + " for session "
              + sessionId);
    }
    if (kind == RunControlKind.STEER) {
      throw new RunControlConflictException(
          kind, "session has no active run, cannot accept STEER for session " + sessionId);
    }
    return directPromote(session, kind, userMessage, now);
  }

  private RunControlMessage directPromote(
      HarnessSessionDO session, RunControlKind kind, AgentMessage userMessage, Instant now) {
    long controlId = controlIdGenerator.newControlMessageId();
    ControlPolicy policy = freezePolicy(session);
    ControlConsumptionMode mode = policy.modeFor(kind);
    RunControlMessage pending =
        new RunControlMessage(
            controlId,
            session.getId(),
            null,
            kind,
            mode,
            userMessage,
            RunControlStatus.PENDING,
            null,
            null,
            now,
            null);
    if (controlStore.insert(pending) != 1) {
      throw new IllegalStateException("cannot insert control message: " + controlId);
    }
    AgentRun newRun =
        runTransactions.submitUserMessage(
            session.getId(), session.getLeafEntryId(), userMessage, now);
    long newRunId = newRun.id();
    long triggerEntryId = newRun.triggerEntryId();
    if (!controlStore.markPromoted(controlId, newRunId, triggerEntryId, now)) {
      throw new IllegalStateException("cannot mark control promoted: " + controlId);
    }
    List<RunEventDraft> events =
        List.of(
            new RunEventDraft(
                RunEventType.FOLLOW_UP_REQUESTED,
                RunEventPayloads.of(
                    "controlId", controlId, "kind", kind.name(), "consumptionMode", mode.name())),
            new RunEventDraft(
                RunEventType.CONTROL_PROMOTED,
                RunEventPayloads.of(
                    "controlId",
                    controlId,
                    "kind",
                    kind.name(),
                    "consumptionMode",
                    mode.name(),
                    "targetRunId",
                    newRunId,
                    "entryId",
                    triggerEntryId)));
    runTransactions.appendExternalEvents(newRunId, events, now);
    return new RunControlMessage(
        controlId,
        session.getId(),
        null,
        kind,
        mode,
        userMessage,
        RunControlStatus.PROMOTED,
        newRunId,
        triggerEntryId,
        now,
        now);
  }

  private RunControlMessage submitAfterActiveRunEnded(
      HarnessSessionDO session,
      long activeHint,
      RunControlKind kind,
      AgentMessage userMessage,
      Instant now,
      String reason) {
    if (session.getActiveRunId() == null && kind == RunControlKind.FOLLOW_UP) {
      return directPromote(session, kind, userMessage, now);
    }
    throw new RunControlConflictException(
        kind,
        reason
            + ", cannot accept "
            + kind
            + " for session "
            + session.getId()
            + " run "
            + activeHint);
  }

  private ControlPolicy freezePolicy(HarnessSessionDO session) {
    Long leafEntryId = session.getLeafEntryId();
    if (leafEntryId == null) {
      throw new IllegalStateException("session has no leaf entry: " + session.getId());
    }
    HarnessSessionEntryDO snapshot =
        sessionEntryMapper.findLatestOnPathByType(
            session.getId(), leafEntryId, SessionEntryType.AGENT_SNAPSHOT.value());
    if (snapshot == null) {
      throw new IllegalStateException(
          "no AGENT_SNAPSHOT on session path for session " + session.getId());
    }
    AgentSnapshotEntryPayload payload =
        (AgentSnapshotEntryPayload)
            payloadCodec.decode(SessionEntryType.AGENT_SNAPSHOT, snapshot.getPayloadJson());
    return ControlPolicyCodec.decode(payload.snapshot().executionPolicyJson());
  }

  private static RunEventType requestedEventType(RunControlKind kind) {
    return kind == RunControlKind.STEER
        ? RunEventType.STEER_REQUESTED
        : RunEventType.FOLLOW_UP_REQUESTED;
  }

  private static boolean isTerminal(HarnessRunDO run) {
    return RunStatus.valueOf(run.getStatus()).terminal();
  }
}
