package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
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

/**
 * Spring-proxy-safe per-Run tool coordination. Each ready Run commits in its own transaction so one
 * malformed Run cannot roll back peers scanned in the same worker tick.
 */
@Service
public class ToolInvocationCoordinationService {
  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;
  private final HarnessRunMapper runMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final HarnessRunEventWriter eventWriter;
  private final RunIdGenerator idGenerator;
  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();

  public ToolInvocationCoordinationService(
      ToolInvocationMapper invocationMapper,
      MysqlToolInvocationStore invocationStore,
      HarnessRunMapper runMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      HarnessRunEventWriter eventWriter,
      RunIdGenerator idGenerator) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Transactional
  public boolean coordinate(long runId, Instant now) {
    // Lock order: Run -> Session -> Root before any sequence allocation or Session Entry writes.
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null || !RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      return false;
    }
    HarnessSessionDO session = eventWriter.lockSessionAndRoot(run.getSessionId());
    if (!Objects.equals(session.getActiveRunId(), runId)) {
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
    eventWriter.appendLocked(
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

  private void appendToolResult(
      HarnessSessionDO session, long runId, ToolInvocation invocation, Instant now) {
    ToolResult result = ToolResultJsonCodec.decode(invocation.resultJson());
    if (result == null || !invocation.toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("ToolResult must match frozen toolCallId");
    }
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

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
