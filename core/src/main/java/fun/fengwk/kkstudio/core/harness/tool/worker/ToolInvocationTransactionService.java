package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically journals Tool state changes and materializes terminal results in source order. */
@Service
public class ToolInvocationTransactionService implements ToolInvocationTransactions {
  private static final int COORDINATION_SCAN_LIMIT = 100;

  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventMapper eventMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final RunIdGenerator idGenerator;
  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();

  public ToolInvocationTransactionService(
      ToolInvocationMapper invocationMapper,
      MysqlToolInvocationStore invocationStore,
      HarnessRunMapper runMapper,
      HarnessRunEventMapper eventMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      RunIdGenerator idGenerator) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  /**
   * Records an idempotent cancellation request; the owner remains responsible for its terminal
   * race.
   */
  @Transactional
  public boolean requestCancel(long invocationId, Instant now) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return invocationMapper.requestCancel(invocationId, utc(now)) == 1;
  }

  @Override
  @Transactional
  public boolean start(ClaimedToolInvocation claimed, Instant now) {
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    appendEvents(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_STARTED,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "toolCallId",
                    invocation.toolCallId(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean appendPartial(
      ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now) {
    List<ToolResult> batch = List.copyOf(Objects.requireNonNull(partials, "partials"));
    if (batch.isEmpty()) {
      return true;
    }
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    for (ToolResult partial : batch) {
      requireResultFor(invocation, partial);
    }
    appendEvents(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_DELTA_BATCH,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "partialResults",
                    batch.stream().map(ToolResultJsonCodec::encode).toList(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus terminalStatus,
      ToolResult result,
      String errorMessage,
      Instant now) {
    if (terminalStatus == null || !terminalStatus.isTerminal()) {
      throw new IllegalArgumentException("terminalStatus must be terminal");
    }
    HarnessRunDO run = lockWaitingRun(claimed.invocation().runId());
    if (run == null) {
      return false;
    }
    ToolInvocation invocation = lockOwned(claimed, now, run.getId());
    if (invocation == null) {
      return false;
    }
    invocation.status().requireTransitionTo(terminalStatus);
    requireResultFor(invocation, result);
    if (invocationMapper.terminateOwned(
            invocation.id(),
            claimed.invocation().leaseOwner(),
            terminalStatus.name(),
            ToolResultJsonCodec.encode(result),
            errorMessage,
            utc(now))
        != 1) {
      return false;
    }
    appendEvents(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_COMPLETED,
                RunEventPayloads.of(
                    "invocationId",
                    invocation.id(),
                    "ordinal",
                    invocation.ordinal(),
                    "status",
                    terminalStatus.name(),
                    "error",
                    result.error(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  @Override
  @Transactional
  public int coordinateReadyRuns(Instant now) {
    int coordinated = 0;
    for (HarnessRunDO run : runMapper.listWaitingTools(COORDINATION_SCAN_LIMIT)) {
      if (coordinate(run.getId(), now)) {
        coordinated++;
      }
    }
    return coordinated;
  }

  @Transactional
  public boolean coordinate(long runId, Instant now) {
    // Deliberately lock Run before Session, matching all cross-aggregate Tool completion paths.
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null || !RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      return false;
    }
    HarnessSessionDO session = sessionMapper.findForUpdate(run.getSessionId());
    if (session == null || !Objects.equals(session.getActiveRunId(), runId)) {
      throw new IllegalStateException("waiting run does not own its session");
    }
    if (invocationMapper.countNonTerminalByRun(runId) != 0) {
      return false;
    }
    List<ToolInvocation> invocations = invocationStore.listByRun(runId);
    if (invocations.isEmpty()) {
      throw new IllegalStateException("waiting run has no tool invocations");
    }
    for (ToolInvocation invocation : invocations) {
      if (!invocation.status().isTerminal() || invocation.resultJson() == null) {
        throw new IllegalStateException("terminal invocation lacks durable ToolResult");
      }
      appendToolResult(session, runId, invocation, now);
    }
    if (runMapper.requeueWaitingTools(runId, utc(now)) != 1) {
      throw new ConcurrentModificationException("waiting run changed during tool coordination");
    }
    appendEvents(
        run,
        List.of(
            new RunEventDraft(
                RunEventType.TOOL_REQUEUED,
                RunEventPayloads.of(
                    "status",
                    RunStatus.QUEUED.name(),
                    "count",
                    invocations.size(),
                    "attempt",
                    run.getAttempt(),
                    "turnIndex",
                    run.getTurnIndex()))),
        now);
    return true;
  }

  private HarnessRunDO lockWaitingRun(long runId) {
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null || !RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      return null;
    }
    return run;
  }

  private ToolInvocation lockOwned(ClaimedToolInvocation claimed, Instant now, long runId) {
    ToolInvocationDO source = invocationMapper.findForUpdate(claimed.invocation().id());
    if (source == null) {
      return null;
    }
    ToolInvocation invocation = invocationStore.toInvocation(source);
    if (invocation.runId() != runId
        || (invocation.status() != ToolInvocationStatus.RUNNING
            && invocation.status() != ToolInvocationStatus.CANCEL_REQUESTED)
        || !Objects.equals(invocation.leaseOwner(), claimed.invocation().leaseOwner())
        || invocation.leaseUntil() == null
        || !invocation.leaseUntil().isAfter(now)) {
      return null;
    }
    return invocation;
  }

  private void appendToolResult(
      HarnessSessionDO session, long runId, ToolInvocation invocation, Instant now) {
    ToolResult result = ToolResultJsonCodec.decode(invocation.resultJson());
    requireResultFor(invocation, result);
    List<AgentMessageContent> contents = new ArrayList<>();
    for (ToolContent content : result.contents()) {
      contents.add(toMessageContent(content));
    }
    ToolResultMessageContent toolResult =
        new ToolResultMessageContent(
            invocation.toolCallId(),
            invocation.toolName(),
            contents,
            result.error(),
            result.detailsJson());
    MessageEntryPayload payload =
        new MessageEntryPayload(new AgentMessage(AgentMessageRole.TOOL, List.of(toolResult)));
    long entryId = idGenerator.newSessionEntryId();
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(entryId);
    entry.setSessionId(session.getId());
    entry.setParentEntryId(session.getLeafEntryId());
    entry.setRunId(runId);
    entry.setEntryType(payload.type().value());
    entry.setPayloadJson(entryCodec.encode(payload));
    entry.setCreateTime(utc(now));
    if (entryMapper.insert(entry) != 1
        || sessionMapper.advanceActiveRunLeaf(
                session.getId(), runId, session.getLeafEntryId(), entryId, utc(now))
            != 1) {
      throw new ConcurrentModificationException("cannot append ToolResult entry");
    }
    session.setLeafEntryId(entryId);
  }

  private AgentMessageContent toMessageContent(ToolContent content) {
    if (content instanceof TextToolContent text) {
      return new TextMessageContent(text.text());
    }
    if (content instanceof JsonToolContent json) {
      return new JsonMessageContent(json.json());
    }
    if (content instanceof ArtifactToolContent artifact) {
      return new ArtifactMessageContent(
          artifact.artifact().artifactId(), artifact.artifact().mediaType(), null);
    }
    throw new IllegalArgumentException("unsupported ToolResult content: " + content.getClass());
  }

  private void requireResultFor(ToolInvocation invocation, ToolResult result) {
    if (result == null || !invocation.toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("ToolResult must match frozen toolCallId");
    }
  }

  private void appendEvents(HarnessRunDO run, List<RunEventDraft> drafts, Instant now) {
    long sequence = run.getEventSequence();
    long finalSequence = Math.addExact(sequence, drafts.size());
    if (runMapper.updateEventSequence(run.getId(), sequence, finalSequence, utc(now)) != 1) {
      throw new ConcurrentModificationException("cannot allocate Tool run event sequence");
    }
    for (RunEventDraft draft : drafts) {
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
      target.setCreateTime(utc(now));
      if (eventMapper.insert(target) != 1) {
        throw new ConcurrentModificationException("cannot append Tool run event");
      }
    }
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
