package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolPolicyResolver;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
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

/** Session leaf、activeRunId、完整 Entry 与 Run transition 的事务服务。 */
@Service
public class HarnessRunTransactionService implements RunTransactions {
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventMapper eventMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final ToolInvocationMapper invocationMapper;
  private final RunIdGenerator idGenerator;
  private final ToolPreparationService toolPreparationService;
  private final ToolPolicyResolver policyResolver;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public HarnessRunTransactionService(
      HarnessRunMapper runMapper,
      HarnessRunEventMapper eventMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      ToolInvocationMapper invocationMapper,
      RunIdGenerator idGenerator,
      ToolPreparationService toolPreparationService,
      ToolPolicyResolver policyResolver) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.toolPreparationService =
        Objects.requireNonNull(toolPreparationService, "toolPreparationService");
    this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
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
  public boolean complete(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      List<RunEventDraft> terminalEvents,
      Instant now) {
    validateAssistant(assistant);
    if (hasAssistantToolCalls(assistant)) {
      throw new IllegalArgumentException("no-tool completion must not contain tool calls");
    }
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
    }
    appendEntry(locked.session(), claimedRun.id(), assistant, now);
    transitionCompleted(claimedRun, RunStatus.SUCCEEDED, now);
    clearActiveRun(claimedRun, now);
    appendEvents(locked.run(), terminalEvents, now);
    return true;
  }

  @Transactional
  public boolean prepareTools(
      AgentRun claimedRun,
      MessageEntryPayload assistant,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path workspaceRoot,
      List<RunEventDraft> assistantEvents,
      Instant now) {
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
    ToolPolicyResolver.ResolvedPolicy policy = policyResolver.resolve(locked.session());
    List<PreparedToolInvocation> prepared =
        toolPreparationService.prepare(
            toolCalls,
            bindings,
            policy.settings(),
            policy.yoloEnabled(),
            workdir,
            workspaceRoot,
            now);
    long assistantEntryId = appendEntry(locked.session(), claimedRun.id(), assistant, now);
    for (PreparedToolInvocation invocation : prepared) {
      if (invocationMapper.insert(toDO(claimedRun.id(), assistantEntryId, invocation, now)) != 1) {
        throw new ConcurrentModificationException("cannot create tool invocation");
      }
    }
    transitionCompleted(claimedRun, RunStatus.WAITING_TOOLS, now);
    appendEvents(locked.run(), toolPreparationEvents(claimedRun, prepared, assistantEvents), now);
    return true;
  }

  @Transactional
  public void appendExternalEvent(long runId, RunEventDraft event, Instant now) {
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    appendEvents(run, List.of(event), now);
  }

  @Override
  @Transactional
  public boolean requeue(
      AgentRun claimedRun, Instant nextAttemptAt, List<RunEventDraft> retryEvents, Instant now) {
    LockedRun locked = lockOwned(claimedRun);
    if (locked == null) {
      return false;
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
    appendEvents(locked.run(), retryEvents, now);
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
    appendEvents(locked.run(), compactionEvents, now);
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
    appendEvents(locked.run(), terminalEvents, now);
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

  private void appendEvents(HarnessRunDO run, List<RunEventDraft> drafts, Instant now) {
    List<RunEventDraft> events = List.copyOf(Objects.requireNonNull(drafts, "eventDrafts"));
    if (events.isEmpty()) {
      throw new IllegalArgumentException("eventDrafts must not be empty");
    }
    long expectedSequence = run.getEventSequence();
    long finalSequence = Math.addExact(expectedSequence, events.size());
    if (runMapper.updateEventSequence(run.getId(), expectedSequence, finalSequence, utc(now))
        != 1) {
      throw new ConcurrentModificationException("cannot allocate run event sequences");
    }
    long sequence = expectedSequence;
    for (RunEventDraft draft : events) {
      RunEvent event =
          new RunEvent(
              idGenerator.newRunEventId(),
              run.getId(),
              ++sequence,
              draft.type(),
              draft.payloadJson(),
              now);
      HarnessRunEventDO target = new HarnessRunEventDO();
      target.setId(event.id());
      target.setRunId(event.runId());
      target.setSequence(event.sequence());
      target.setEventType(event.type().value());
      target.setPayloadJson(event.payloadJson());
      target.setCreateTime(utc(event.createdAt()));
      if (eventMapper.insert(target) != 1) {
        throw new ConcurrentModificationException("cannot append run event");
      }
    }
    run.setEventSequence(finalSequence);
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
}
