package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolPolicyResolver;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.run.RunTransactions;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.PreparedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPreparationService;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HarnessRunTransactionService implements RunTransactions {
  private final HarnessRunMapper runMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final ToolInvocationMapper invocationMapper;
  private final RunIdGenerator idGenerator;
  private final HarnessRunEventWriter eventWriter;
  private final ModelUsageRecordStore usageRecordStore;
  private final ModelUsageRecordIdGenerator usageRecordIdGenerator;
  private final ToolPreparationService toolPreparationService;
  private final ToolPolicyResolver policyResolver;
  private final RunControlMessageStore controlStore;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public HarnessRunTransactionService(
      HarnessRunMapper runMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      ToolInvocationMapper invocationMapper,
      RunIdGenerator idGenerator,
      HarnessRunEventWriter eventWriter,
      ModelUsageRecordStore usageRecordStore,
      ModelUsageRecordIdGenerator usageRecordIdGenerator,
      ToolPreparationService toolPreparationService,
      ToolPolicyResolver policyResolver,
      RunControlMessageStore controlStore) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
    this.usageRecordStore = Objects.requireNonNull(usageRecordStore, "usageRecordStore");
    this.usageRecordIdGenerator =
        Objects.requireNonNull(usageRecordIdGenerator, "usageRecordIdGenerator");
    this.toolPreparationService =
        Objects.requireNonNull(toolPreparationService, "toolPreparationService");
    this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
    this.controlStore = Objects.requireNonNull(controlStore, "controlStore");
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
  public boolean consumeSteering(AgentRun claimedRun, Instant now) {
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      cancelOwned(locked, now, null);
      return false;
    }
    List<RunControlMessage> pending =
        controlStore.listPendingByRun(claimedRun.id(), RunControlKind.STEER);
    if (pending.isEmpty()) {
      return true;
    }
    ControlConsumptionMode mode = pending.get(0).consumptionMode();
    List<RunControlMessage> batch =
        mode == ControlConsumptionMode.ONE_AT_A_TIME
            ? List.of(pending.get(0))
            : List.copyOf(pending);
    List<ControlConsumption> consumed =
        applyControlEntries(locked.session(), claimedRun.id(), batch, now);
    List<RunEventDraft> events = new ArrayList<>();
    for (ControlConsumption c : consumed) {
      events.add(
          new RunEventDraft(
              RunEventType.STEER_CONSUMED,
              RunEventPayloads.forAttempt(
                  claimedRun, "controlId", c.control().id(), "entryId", c.entryId())));
    }
    appendEventsInternal(locked.run(), events, now);
    return true;
  }

  @Override
  @Transactional
  public boolean complete(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      RunEventDraft assistantCompleted,
      Instant now) {
    Objects.requireNonNull(usageDraft, "usageDraft");
    validateAssistant(assistant);
    if (hasAssistantToolCalls(assistant)) {
      throw new IllegalArgumentException("no-tool completion must not contain tool calls");
    }
    if (assistantCompleted == null
        || assistantCompleted.type() != RunEventType.ASSISTANT_COMPLETED) {
      throw new IllegalArgumentException("complete requires exactly one ASSISTANT_COMPLETED event");
    }
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      cancelOwned(locked, now, null);
      return true;
    }
    long assistantEntryId = appendEntry(locked.session(), locked.run().getId(), assistant, now);
    usageRecordStore.insert(
        new ModelUsageRecord(
            usageRecordIdGenerator.newModelUsageRecordId(),
            locked.session().getId(),
            locked.run().getId(),
            assistantEntryId,
            locked.run().getAttempt(),
            locked.run().getTurnIndex(),
            usageDraft,
            now));

    List<RunControlMessage> pendingSteer =
        controlStore.listPendingByRun(locked.run().getId(), RunControlKind.STEER);
    if (!pendingSteer.isEmpty()) {
      doRequeueAdvanceTurn(claimedRun, now);
      appendEventsInternal(
          locked.run(),
          List.of(
              assistantCompleted,
              new RunEventDraft(
                  RunEventType.RUN_REQUEUED,
                  RunEventPayloads.forAttempt(
                      claimedRun, "reason", "steering_pending", "count", pendingSteer.size()))),
          now);
      return true;
    }

    List<RunControlMessage> pendingFollowUp =
        controlStore.listPendingByRun(locked.run().getId(), RunControlKind.FOLLOW_UP);
    if (!pendingFollowUp.isEmpty()) {
      ControlConsumptionMode mode = pendingFollowUp.get(0).consumptionMode();
      List<RunControlMessage> batch =
          mode == ControlConsumptionMode.ONE_AT_A_TIME
              ? List.of(pendingFollowUp.get(0))
              : List.copyOf(pendingFollowUp);
      List<ControlConsumption> consumed =
          applyControlEntries(locked.session(), locked.run().getId(), batch, now);
      doRequeueAdvanceTurn(claimedRun, now);
      List<RunEventDraft> events = new ArrayList<>();
      events.add(assistantCompleted);
      for (ControlConsumption c : consumed) {
        events.add(
            new RunEventDraft(
                RunEventType.FOLLOW_UP_CONSUMED,
                RunEventPayloads.forAttempt(
                    claimedRun, "controlId", c.control().id(), "entryId", c.entryId())));
      }
      events.add(
          new RunEventDraft(
              RunEventType.RUN_REQUEUED,
              RunEventPayloads.forAttempt(
                  claimedRun, "reason", "follow_up_consumed", "count", batch.size())));
      appendEventsInternal(locked.run(), events, now);
      return true;
    }

    transitionCompleted(claimedRun, RunStatus.SUCCEEDED, now);
    clearActiveRun(claimedRun, now);
    appendEventsInternal(
        locked.run(),
        List.of(
            assistantCompleted,
            new RunEventDraft(
                RunEventType.RUN_COMPLETED,
                RunEventPayloads.forAttempt(claimedRun, "status", "SUCCEEDED"))),
        now);
    return true;
  }

  @Transactional
  public boolean prepareTools(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path environmentRoot,
      List<RunEventDraft> assistantEvents,
      Instant now) {
    Objects.requireNonNull(usageDraft, "usageDraft");
    validateAssistant(assistant);
    validateAssistantToolCalls(assistant, toolCalls);
    if (assistantEvents == null
        || assistantEvents.size() != 1
        || assistantEvents.get(0) == null
        || assistantEvents.get(0).type() != RunEventType.ASSISTANT_COMPLETED) {
      throw new IllegalArgumentException(
          "tool preparation requires exactly one assistant_completed event");
    }
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      cancelOwned(locked, now, null);
      return true;
    }
    ToolPolicyResolver.ResolvedPolicy policy = policyResolver.resolve(locked.session());
    List<PreparedToolInvocation> prepared =
        toolPreparationService.prepare(
            toolCalls,
            bindings,
            policy.settings(),
            policy.yoloEnabled(),
            workdir,
            environmentRoot,
            now);
    long assistantEntryId = appendEntry(locked.session(), locked.run().getId(), assistant, now);
    usageRecordStore.insert(
        new ModelUsageRecord(
            usageRecordIdGenerator.newModelUsageRecordId(),
            locked.session().getId(),
            locked.run().getId(),
            assistantEntryId,
            locked.run().getAttempt(),
            locked.run().getTurnIndex(),
            usageDraft,
            now));
    for (PreparedToolInvocation invocation : prepared) {
      if (invocationMapper.insert(toDO(locked.run().getId(), assistantEntryId, invocation, now))
          != 1) {
        throw new ConcurrentModificationException("cannot create tool invocation");
      }
    }
    transitionCompleted(claimedRun, RunStatus.WAITING_TOOLS, now);
    appendEventsInternal(
        locked.run(), toolPreparationEvents(claimedRun, prepared, assistantEvents), now);
    return true;
  }

  @Transactional
  public void appendExternalEvent(long runId, RunEventDraft event, Instant now) {
    appendExternalEvents(runId, List.of(event), now);
  }

  /** 在同一事务内按序追加外部事件。 */
  @Transactional
  public void appendExternalEvents(long runId, List<RunEventDraft> events, Instant now) {
    eventWriter.lockAndAppend(runId, events, now);
  }

  @Override
  @Transactional
  public boolean requeue(
      AgentRun claimedRun, Instant nextAttemptAt, List<RunEventDraft> retryEvents, Instant now) {
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      cancelOwned(locked, now, null);
      return true;
    }
    claimedRun.status().requireTransitionTo(RunStatus.QUEUED);
    if (runMapper.requeueOwned(
            claimedRun.id(),
            claimedRun.leaseOwner(),
            claimedRun.attempt(),
            utc(nextAttemptAt),
            utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during retry scheduling");
    }
    appendEventsInternal(locked.run(), retryEvents, now);
    return true;
  }

  @Override
  @Transactional
  public boolean compactAndRequeue(
      AgentRun claimedRun,
      CompactionEntryPayload compaction,
      Instant nextAttemptAt,
      List<RunEventDraft> compactionEvents,
      Instant now) {
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      cancelOwned(locked, now, null);
      return true;
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
    appendEventsInternal(locked.run(), compactionEvents, now);
    return true;
  }

  @Override
  @Transactional
  public boolean terminate(
      AgentRun claimedRun,
      RunStatus terminalStatus,
      List<RunEventDraft> terminalEvents,
      Instant now) {
    if (terminalStatus != RunStatus.FAILED && terminalStatus != RunStatus.CANCELLED) {
      throw new IllegalArgumentException("terminate only accepts FAILED or CANCELLED");
    }
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    if (locked.run().getCancelRequestedAt() != null) {
      List<RunEventDraft> filtered = new ArrayList<>();
      for (RunEventDraft evt : terminalEvents) {
        if (evt.type() != RunEventType.RUN_FAILED) {
          filtered.add(evt);
        }
      }
      cancelOwned(locked, now, filtered);
      return true;
    }
    if (terminalStatus == RunStatus.CANCELLED) {
      cancelOwned(locked, now, terminalEvents);
      return true;
    }
    claimedRun.status().requireTransitionTo(RunStatus.FAILED);
    if (runMapper.terminateOwned(
            claimedRun.id(),
            claimedRun.leaseOwner(),
            claimedRun.attempt(),
            RunStatus.FAILED.name(),
            utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during terminal transition");
    }
    List<RunControlMessage> pending = controlStore.listPendingBySession(claimedRun.sessionId());
    if (!pending.isEmpty()) {
      clearActiveRun(claimedRun, now);
      Promotion promotion = promoteToNewRun(locked.session(), pending, now);
      List<RunEventDraft> allEvents = new ArrayList<>(terminalEvents);
      for (ControlConsumption consumed : promotion.controls()) {
        allEvents.add(
            new RunEventDraft(
                RunEventType.CONTROL_PROMOTED,
                RunEventPayloads.forAttempt(
                    claimedRun,
                    "controlId",
                    consumed.control().id(),
                    "kind",
                    consumed.control().kind().name(),
                    "targetRunId",
                    promotion.runId(),
                    "entryId",
                    consumed.entryId())));
      }
      appendEventsInternal(locked.run(), allEvents, now);
    } else {
      clearActiveRun(claimedRun, now);
      appendEventsInternal(locked.run(), terminalEvents, now);
    }
    return true;
  }

  private void cancelOwned(LockedRun locked, Instant now, List<RunEventDraft> extraEvents) {
    HarnessRunDO run = locked.run();
    AgentRun runSnapshot = toRun(run);
    runSnapshot.status().requireTransitionTo(RunStatus.CANCELLED);
    if (runMapper.terminateOwned(
            run.getId(),
            run.getLeaseOwner(),
            run.getAttempt(),
            RunStatus.CANCELLED.name(),
            utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during cancel");
    }
    clearActiveRun(runSnapshot, now);
    controlStore.clearPendingBySession(run.getSessionId(), now);
    List<RunEventDraft> events = new ArrayList<>();
    boolean hasRunCancelled = false;
    if (extraEvents != null) {
      for (RunEventDraft evt : extraEvents) {
        if (evt.type() != RunEventType.RUN_FAILED) {
          events.add(evt);
        }
        if (evt.type() == RunEventType.RUN_CANCELLED) {
          hasRunCancelled = true;
        }
      }
    }
    if (!hasRunCancelled) {
      events.add(
          new RunEventDraft(
              RunEventType.RUN_CANCELLED,
              RunEventPayloads.forAttempt(
                  runSnapshot,
                  "reason",
                  run.getCancelRequestedAt() != null ? "cancel_requested" : "cancelled")));
    }
    appendEventsInternal(run, events, now);
  }

  private Promotion promoteToNewRun(
      HarnessSessionDO session, List<RunControlMessage> pending, Instant now) {
    long newRunId = idGenerator.newRunId();
    LocalDateTime timestamp = utc(now);
    long[] entryIds = new long[pending.size()];
    List<ControlConsumption> promoted = new ArrayList<>();
    for (int i = 0; i < pending.size(); i++) {
      entryIds[i] = idGenerator.newSessionEntryId();
    }
    Long prevEntryId = session.getLeafEntryId();
    for (int i = 0; i < pending.size(); i++) {
      RunControlMessage control = pending.get(i);
      MessageEntryPayload payload = new MessageEntryPayload(control.message());
      if (entryMapper.insert(
              entry(
                  entryIds[i],
                  session.getId(),
                  prevEntryId,
                  newRunId,
                  payload.type().value(),
                  payloadCodec.encode(payload),
                  timestamp))
          != 1) {
        throw new ConcurrentModificationException("cannot insert promoted entry");
      }
      prevEntryId = entryIds[i];
    }
    if (runMapper.insert(queuedRun(newRunId, session.getId(), entryIds[0], timestamp)) != 1) {
      throw new ConcurrentModificationException("cannot insert promoted run");
    }
    if (sessionMapper.attachRun(
            session.getId(),
            session.getLeafEntryId(),
            entryIds[pending.size() - 1],
            newRunId,
            timestamp)
        != 1) {
      throw new ConcurrentModificationException("cannot attach promoted run");
    }
    session.setLeafEntryId(entryIds[pending.size() - 1]);
    session.setActiveRunId(newRunId);
    for (int i = 0; i < pending.size(); i++) {
      RunControlMessage control = pending.get(i);
      if (!controlStore.markPromoted(control.id(), newRunId, entryIds[i], now)) {
        throw new ConcurrentModificationException(
            "control already consumed during promotion: " + control.id());
      }
      promoted.add(new ControlConsumption(control, entryIds[i]));
    }
    return new Promotion(newRunId, List.copyOf(promoted));
  }

  private List<ControlConsumption> applyControlEntries(
      HarnessSessionDO session, long currentRunId, List<RunControlMessage> batch, Instant now) {
    long currentLeaf = session.getLeafEntryId();
    LocalDateTime timestamp = utc(now);
    List<ControlConsumption> result = new ArrayList<>();
    for (RunControlMessage control : batch) {
      long entryId = idGenerator.newSessionEntryId();
      MessageEntryPayload payload = new MessageEntryPayload(control.message());
      if (entryMapper.insert(
              entry(
                  entryId,
                  session.getId(),
                  currentLeaf,
                  currentRunId,
                  payload.type().value(),
                  payloadCodec.encode(payload),
                  timestamp))
          != 1) {
        throw new ConcurrentModificationException("cannot insert control entry");
      }
      if (sessionMapper.advanceActiveRunLeaf(
              session.getId(), currentRunId, currentLeaf, entryId, timestamp)
          != 1) {
        throw new ConcurrentModificationException(
            "session leaf changed during control consumption");
      }
      currentLeaf = entryId;
      if (!controlStore.markConsumed(control.id(), currentRunId, entryId, now)) {
        throw new ConcurrentModificationException("control already consumed: " + control.id());
      }
      result.add(new ControlConsumption(control, entryId));
    }
    session.setLeafEntryId(currentLeaf);
    return result;
  }

  private void doRequeueAdvanceTurn(AgentRun claimedRun, Instant now) {
    claimedRun.status().requireTransitionTo(RunStatus.QUEUED);
    if (runMapper.requeueAdvanceTurn(
            claimedRun.id(), claimedRun.leaseOwner(), claimedRun.attempt(), utc(now))
        != 1) {
      throw new ConcurrentModificationException("run ownership lost during requeue advance turn");
    }
  }

  private LockedRun lockOwned(AgentRun claimedRun) {
    HarnessRunDO run = runMapper.findForUpdate(claimedRun.id());
    if (run == null
        || !RunStatus.RUNNING.name().equals(run.getStatus())
        || !Objects.equals(run.getLeaseOwner(), claimedRun.leaseOwner())
        || run.getAttempt() != claimedRun.attempt()) {
      return null;
    }
    // Run is locked; Session then Root before lower-order locks or event id allocation.
    HarnessSessionDO session = eventWriter.lockSessionAndRoot(claimedRun.sessionId());
    if (!Objects.equals(session.getActiveRunId(), claimedRun.id())) {
      throw new IllegalStateException("session active run does not match claimed run");
    }
    return new LockedRun(run, session);
  }

  private void appendEventsInternal(HarnessRunDO run, List<RunEventDraft> drafts, Instant now) {
    eventWriter.appendLocked(run, drafts, now);
  }

  private long appendEntry(
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
    return entryId;
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

  private boolean hasAssistantToolCalls(MessageEntryPayload assistant) {
    return assistant.message().contents().stream()
        .anyMatch(ToolCallMessageContent.class::isInstance);
  }

  private void validateAssistantToolCalls(MessageEntryPayload assistant, List<ToolCall> toolCalls) {
    List<ToolCall> expected = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    List<ToolCall> persisted =
        assistant.message().contents().stream()
            .filter(ToolCallMessageContent.class::isInstance)
            .map(ToolCallMessageContent.class::cast)
            .map(
                content ->
                    new ToolCall(content.toolCallId(), content.toolName(), content.argumentsJson()))
            .toList();
    if (!persisted.equals(expected)) {
      throw new IllegalArgumentException(
          "assistant tool calls must exactly match invocation source order");
    }
  }

  private List<RunEventDraft> toolPreparationEvents(
      AgentRun run, List<PreparedToolInvocation> prepared, List<RunEventDraft> assistantEvents) {
    List<RunEventDraft> events =
        new ArrayList<>(List.copyOf(Objects.requireNonNull(assistantEvents, "assistantEvents")));
    for (PreparedToolInvocation invocation : prepared) {
      events.add(
          new RunEventDraft(
              RunEventType.TOOL_PREPARED,
              RunEventPayloads.forAttempt(
                  run,
                  "invocationId",
                  invocation.id(),
                  "ordinal",
                  invocation.ordinal(),
                  "toolCallId",
                  invocation.call().id(),
                  "toolName",
                  invocation.call().toolName(),
                  "arguments",
                  invocation.call().argumentsJson(),
                  "status",
                  invocation.initialStatus().name())));
      if (invocation.initialStatus() == ToolInvocationStatus.WAITING_APPROVAL) {
        events.add(
            new RunEventDraft(
                RunEventType.PERMISSION_REQUESTED,
                RunEventPayloads.forAttempt(
                    run,
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "tool",
                    invocation.promptPreview().tool(),
                    "workdir",
                    invocation.promptPreview().workdir(),
                    "arguments",
                    invocation.promptPreview().arguments())));
      }
    }
    events.add(
        new RunEventDraft(
            RunEventType.RUN_WAITING,
            RunEventPayloads.forAttempt(run, "status", RunStatus.WAITING_TOOLS.name())));
    return List.copyOf(events);
  }

  private ToolInvocationDO toDO(
      long runId, long assistantEntryId, PreparedToolInvocation source, Instant now) {
    ToolInvocationDO target = new ToolInvocationDO();
    target.setId(source.id());
    target.setRunId(runId);
    target.setAssistantEntryId(assistantEntryId);
    target.setOrdinal(source.ordinal());
    target.setToolCallId(source.call().id());
    target.setToolName(source.call().toolName());
    target.setToolVersion(source.binding().descriptor().version());
    target.setTargetType(source.binding().targetType().name());
    target.setEnvironmentId(source.binding().environmentId());
    target.setArgumentsJson(source.call().argumentsJson());
    target.setStatus(source.initialStatus().name());
    target.setPermissionAction(source.permissionAction().name());
    target.setSideEffect(source.binding().descriptor().sideEffect().name());
    target.setDeadlineAt(utc(source.deadlineAt()));
    target.setResultJson(source.resultJson());
    target.setErrorMessage(source.errorMessage());
    LocalDateTime timestamp = utc(now);
    target.setCreateTime(timestamp);
    target.setFinishedAt(source.initialStatus().isTerminal() ? timestamp : null);
    target.setUpdateTime(timestamp);
    return target;
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

  private record ControlConsumption(RunControlMessage control, long entryId) {}

  private record Promotion(long runId, List<ControlConsumption> controls) {}
}
