package fun.fengwk.kkstudio.core.harness.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.SessionIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.SubagentTask;
import fun.fengwk.kkstudio.harness.runtime.task.TaskCommand;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskPolicy;
import fun.fengwk.kkstudio.harness.runtime.task.TaskReport;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspacePolicy;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database implementation of durable task start/resume/inspection. The lock order is parent Run,
 * parent Session, root Session, parent Invocation, then child Session/Run; root locking serializes
 * depth and tree concurrency checks.
 */
@Service
public class DatabaseTaskRuntime implements TaskRuntime {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventMapper eventMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final ToolInvocationMapper invocationMapper;
  private final HarnessSubagentTaskMapper taskMapper;
  private final AgentDefinitionMapper agentMapper;
  private final RunIdGenerator runIds;
  private final SessionIdGenerator sessionIds;
  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();

  public DatabaseTaskRuntime(
      HarnessRunMapper runMapper,
      HarnessRunEventMapper eventMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      ToolInvocationMapper invocationMapper,
      HarnessSubagentTaskMapper taskMapper,
      AgentDefinitionMapper agentMapper,
      RunIdGenerator runIds,
      SessionIdGenerator sessionIds) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
    this.runIds = Objects.requireNonNull(runIds, "runIds");
    this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
  }

  @Override
  @Transactional
  public TaskInspection startOrResume(
      ToolExecutionContext context, TaskCommand command, Instant now) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(command, "command");
    LocalDateTime timestamp = utc(now);

    HarnessRunDO parentRun = requireParentRun(context);
    HarnessSessionDO parent = requireSessionForUpdate(parentRun.getSessionId());
    if (parent.getWorkspaceId() != context.workspaceId()) {
      throw new IllegalArgumentException(
          "task execution context workspace does not match parent session");
    }
    HarnessSessionDO root = lockRoot(parent);
    ToolInvocationDO invocation = requireTaskInvocation(context, parentRun);
    HarnessSubagentTaskDO replay = taskMapper.findForUpdate(context.invocationId());
    if (replay != null) {
      return inspection(replay);
    }

    AgentSnapshot parentSnapshot = parentSnapshot(parent.getId());
    if (!parentSnapshot.allowedSubagents().contains(command.subagentType())) {
      throw new IllegalArgumentException("subagent is not allowed: " + command.subagentType());
    }
    TaskPolicy parentPolicy = parsePolicy(parentSnapshot.executionPolicyJson());
    if (parent.getDepth() >= parentPolicy.maxDepth()) {
      throw new IllegalStateException("subagent maximum depth exceeded");
    }
    if (taskMapper.countActiveDirect(parent.getId()) >= parentPolicy.maxDirect()) {
      throw new IllegalStateException("subagent direct concurrency limit exceeded");
    }
    if (parentPolicy.maxTotal() != null
        && taskMapper.countActiveRoot(parent.getWorkspaceId(), root.getId())
            >= parentPolicy.maxTotal()) {
      throw new IllegalStateException("subagent root concurrency limit exceeded");
    }

    AgentDefinitionDO target =
        agentMapper.getByWorkspaceIdAndName(parent.getWorkspaceId(), command.subagentType());
    if (target == null) {
      throw new IllegalArgumentException(
          "unknown subagent in workspace: " + command.subagentType());
    }
    if (command.sessionId() == null) {
      return createChild(context, command, parentRun, parent, root, target, timestamp, now);
    }
    return resumeChild(context, command, parentRun, parent, root, target, timestamp, now);
  }

  @Override
  @Transactional
  public TaskInspection inspect(long parentInvocationId, Instant now) {
    HarnessSubagentTaskDO task = taskMapper.findForUpdate(parentInvocationId);
    if (task == null) {
      throw new IllegalArgumentException("unknown task invocation: " + parentInvocationId);
    }
    if (!TaskState.RUNNING.name().equals(task.getStatus())) {
      return inspection(task);
    }
    HarnessRunDO childRun = runMapper.findForUpdate(task.getChildRunId());
    if (childRun == null) {
      throw new IllegalStateException("task child run is missing");
    }
    LocalDateTime timestamp = utc(now);
    if (!isTerminal(childRun)) {
      if (childRun.getTurnIndex() >= task.getMaxTurns()
          || (task.getIdleTimeoutMillis() != null
              && childRun
                  .getUpdateTime()
                  .plus(task.getIdleTimeoutMillis(), ChronoUnit.MILLIS)
                  .isBefore(timestamp))) {
        requestChildCancellation(task.getChildRunId(), timestamp);
      }
      return inspection(task);
    }

    TaskState terminal = taskState(childRun.getStatus());
    TaskReport report = report(task, childRun, terminal);
    if (taskMapper.complete(
            task.getParentInvocationId(), terminal.name(), encode(report), timestamp)
        == 1) {
      ToolInvocationDO parentInvocation =
          invocationMapper.findForUpdate(task.getParentInvocationId());
      if (parentInvocation != null) {
        HarnessRunDO parentRun = runMapper.findForUpdate(parentInvocation.getRunId());
        if (parentRun != null) {
          appendEvent(
              parentRun,
              RunEventType.SUBAGENT_COMPLETED,
              RunEventPayloads.of(
                  "childSessionId", task.getChildSessionId(),
                  "childRunId", task.getChildRunId(),
                  "status", terminal.name(),
                  "attempt", parentRun.getAttempt(),
                  "turnIndex", parentRun.getTurnIndex()),
              timestamp);
        }
      }
    }
    return inspection(requireTask(parentInvocationId));
  }

  @Override
  @Transactional
  public void cancelTree(long parentInvocationId, Instant now) {
    HarnessSubagentTaskDO rootTask = taskMapper.findForUpdate(parentInvocationId);
    if (rootTask == null) {
      return;
    }
    LocalDateTime timestamp = utc(now);
    ArrayDeque<HarnessSubagentTaskDO> pending = new ArrayDeque<>();
    pending.add(rootTask);
    while (!pending.isEmpty()) {
      HarnessSubagentTaskDO task = pending.removeFirst();
      requestChildCancellation(task.getChildRunId(), timestamp);
      pending.addAll(taskMapper.listByParentSession(task.getChildSessionId()));
    }
    ToolInvocationDO invocation = invocationMapper.findForUpdate(parentInvocationId);
    if (invocation != null) {
      HarnessRunDO parentRun = runMapper.findForUpdate(invocation.getRunId());
      if (parentRun != null) {
        appendEvent(
            parentRun,
            RunEventType.SUBAGENT_CANCEL_REQUESTED,
            RunEventPayloads.of(
                "childSessionId", rootTask.getChildSessionId(),
                "childRunId", rootTask.getChildRunId(),
                "attempt", parentRun.getAttempt(),
                "turnIndex", parentRun.getTurnIndex()),
            timestamp);
      }
    }
  }

  private TaskInspection createChild(
      ToolExecutionContext context,
      TaskCommand command,
      HarnessRunDO parentRun,
      HarnessSessionDO parent,
      HarnessSessionDO root,
      AgentDefinitionDO target,
      LocalDateTime timestamp,
      Instant now) {
    long childSessionId = sessionIds.newSessionId();
    long snapshotEntryId = sessionIds.newEntryId();
    long promptEntryId = sessionIds.newEntryId();
    long childRunId = runIds.newRunId();
    AgentSnapshot targetSnapshot = snapshot(target);
    insertSession(
        childSessionId,
        parent.getWorkspaceId(),
        target.getId(),
        parent.getId(),
        root.getId(),
        context.invocationId(),
        parent.getDepth() + 1,
        promptEntryId,
        childRunId,
        timestamp);
    insertEntry(
        snapshotEntryId,
        childSessionId,
        null,
        null,
        SessionEntryType.AGENT_SNAPSHOT,
        new AgentSnapshotEntryPayload(targetSnapshot),
        timestamp);
    insertEntry(
        promptEntryId,
        childSessionId,
        snapshotEntryId,
        childRunId,
        SessionEntryType.MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent(command.prompt())))),
        timestamp);
    insertQueuedRun(childRunId, childSessionId, promptEntryId, timestamp);
    TaskPolicy targetPolicy = parsePolicy(targetSnapshot.executionPolicyJson());
    insertTask(
        context.invocationId(),
        parent.getId(),
        childSessionId,
        childRunId,
        target.getName(),
        command.workspacePolicy(),
        targetPolicy,
        timestamp);
    appendEvent(
        parentRun,
        RunEventType.SUBAGENT_STARTED,
        taskPayload(
            childSessionId, childRunId, target.getName(), command.workspacePolicy(), parentRun),
        timestamp);
    appendEvent(
        requireRun(childRunId),
        RunEventType.SUBAGENT_STARTED,
        RunEventPayloads.of(
            "parentInvocationId", context.invocationId(), "attempt", 0, "turnIndex", 0),
        timestamp);
    return inspection(requireTask(context.invocationId()));
  }

  private TaskInspection resumeChild(
      ToolExecutionContext context,
      TaskCommand command,
      HarnessRunDO parentRun,
      HarnessSessionDO parent,
      HarnessSessionDO root,
      AgentDefinitionDO target,
      LocalDateTime timestamp,
      Instant now) {
    HarnessSessionDO child = requireSessionForUpdate(command.sessionId());
    if (child.getWorkspaceId() != parent.getWorkspaceId()
        || child.getRootSessionId() != root.getId()
        || !Objects.equals(child.getParentSessionId(), parent.getId())
        || !Objects.equals(child.getAgentDefinitionId(), target.getId())) {
      throw new IllegalArgumentException(
          "resume child does not match parent hierarchy or target agent");
    }
    if (child.getActiveRunId() != null) {
      throw new IllegalStateException("subagent child already has an active run");
    }
    long childRunId = runIds.newRunId();
    long promptEntryId = sessionIds.newEntryId();
    insertEntry(
        promptEntryId,
        child.getId(),
        child.getLeafEntryId(),
        childRunId,
        SessionEntryType.MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent(command.prompt())))),
        timestamp);
    insertQueuedRun(childRunId, child.getId(), promptEntryId, timestamp);
    if (sessionMapper.attachRun(
            child.getId(), child.getLeafEntryId(), promptEntryId, childRunId, timestamp)
        != 1) {
      throw new IllegalStateException("subagent child changed before resume");
    }
    TaskPolicy targetPolicy = parsePolicy(snapshot(target).executionPolicyJson());
    insertTask(
        context.invocationId(),
        parent.getId(),
        child.getId(),
        childRunId,
        target.getName(),
        command.workspacePolicy(),
        targetPolicy,
        timestamp);
    appendEvent(
        parentRun,
        RunEventType.SUBAGENT_RESUMED,
        taskPayload(
            child.getId(), childRunId, target.getName(), command.workspacePolicy(), parentRun),
        timestamp);
    appendEvent(
        requireRun(childRunId),
        RunEventType.SUBAGENT_RESUMED,
        RunEventPayloads.of(
            "parentInvocationId", context.invocationId(), "attempt", 0, "turnIndex", 0),
        timestamp);
    return inspection(requireTask(context.invocationId()));
  }

  private HarnessRunDO requireParentRun(ToolExecutionContext context) {
    HarnessRunDO run = runMapper.findForUpdate(context.runId());
    if (run == null || !RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      throw new IllegalStateException("parent run is not waiting for tools");
    }
    return run;
  }

  private ToolInvocationDO requireTaskInvocation(
      ToolExecutionContext context, HarnessRunDO parentRun) {
    ToolInvocationDO invocation = invocationMapper.findForUpdate(context.invocationId());
    if (invocation == null
        || !Objects.equals(invocation.getRunId(), parentRun.getId())
        || !"task".equals(invocation.getToolName())
        || !"CONTROL".equals(invocation.getTargetType())
        || !"RUNNING".equals(invocation.getStatus())) {
      throw new IllegalStateException("parent invocation is not a running task control invocation");
    }
    return invocation;
  }

  private HarnessSessionDO lockRoot(HarnessSessionDO parent) {
    HarnessSessionDO root = requireSessionForUpdate(parent.getRootSessionId());
    if (root.getId().equals(parent.getId())) {
      return root;
    }
    if (root.getParentSessionId() != null || root.getRootSessionId() != root.getId()) {
      throw new IllegalStateException("invalid root session");
    }
    return root;
  }

  private AgentSnapshot parentSnapshot(long sessionId) {
    HarnessSessionEntryDO entry =
        entryMapper.findLatestByType(sessionId, SessionEntryType.AGENT_SNAPSHOT.value());
    if (entry == null) {
      throw new IllegalStateException("parent session has no frozen agent snapshot");
    }
    SessionEntryPayload payload =
        entryCodec.decode(SessionEntryType.AGENT_SNAPSHOT, entry.getPayloadJson());
    return ((AgentSnapshotEntryPayload) payload).snapshot();
  }

  private AgentSnapshot snapshot(AgentDefinitionDO definition) {
    try {
      JsonNode config = OBJECT_MAPPER.readTree(definition.getConfigJson());
      List<String> tools = strings(config, "tools");
      List<String> skills = strings(config, "skills");
      List<String> allowed = strings(config, "allowedSubagents");
      JsonNode policy = config.path("executionPolicy");
      return new AgentSnapshot(
          definition.getSystemPrompt(),
          String.valueOf(definition.getModelId()),
          definition.getVariant(),
          tools,
          skills,
          allowed,
          OBJECT_MAPPER.writeValueAsString(
              policy.isMissingNode() ? OBJECT_MAPPER.createObjectNode() : policy));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("target agent config is invalid", error);
    }
  }

  private List<String> strings(JsonNode config, String name) {
    JsonNode values = config.path(name);
    if (values.isMissingNode() || values.isNull()) {
      return List.of();
    }
    if (!values.isArray()) {
      throw new IllegalArgumentException("agent config " + name + " must be an array");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual() || value.textValue().isBlank()) {
        throw new IllegalArgumentException(
            "agent config " + name + " must contain non-blank strings");
      }
      result.add(value.textValue());
    }
    return List.copyOf(result);
  }

  private TaskPolicy parsePolicy(String json) {
    try {
      JsonNode policy = OBJECT_MAPPER.readTree(json);
      return new TaskPolicy(
          positive(policy, "maxDepth", TaskPolicy.DEFAULT_MAX_DEPTH),
          positive(policy, "maxDirectSubagents", TaskPolicy.DEFAULT_MAX_DIRECT),
          optionalPositive(policy, "maxTotalSubagents"),
          positive(policy, "maxTurns", TaskPolicy.DEFAULT_MAX_TURNS),
          optionalDuration(policy, "idleTimeoutMillis"));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("execution policy must be valid JSON", error);
    }
  }

  private int positive(JsonNode policy, String field, int fallback) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return fallback;
    }
    if (!value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return value.intValue();
  }

  private Integer optionalPositive(JsonNode policy, String field) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return value.intValue();
  }

  private Duration optionalDuration(JsonNode policy, String field) {
    JsonNode value = policy.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    if (!value.canConvertToLong() || value.longValue() <= 0) {
      throw new IllegalArgumentException("execution policy " + field + " must be positive");
    }
    return Duration.ofMillis(value.longValue());
  }

  private void insertSession(
      long id,
      long workspaceId,
      long agentDefinitionId,
      long parentSessionId,
      long rootSessionId,
      long parentInvocationId,
      int depth,
      long leafEntryId,
      long activeRunId,
      LocalDateTime timestamp) {
    HarnessSessionDO session = new HarnessSessionDO();
    session.setId(id);
    session.setWorkspaceId(workspaceId);
    session.setAgentDefinitionId(agentDefinitionId);
    session.setTitle(null);
    session.setLeafEntryId(leafEntryId);
    session.setActiveRunId(activeRunId);
    session.setParentSessionId(parentSessionId);
    session.setRootSessionId(rootSessionId);
    session.setParentInvocationId(parentInvocationId);
    session.setDepth(depth);
    session.setYoloEnabled(false);
    session.setVersion(0L);
    session.setCreateTime(timestamp);
    session.setUpdateTime(timestamp);
    if (sessionMapper.insert(session) != 1) {
      throw new IllegalStateException("cannot create child session");
    }
  }

  private void insertEntry(
      long id,
      long sessionId,
      Long parentEntryId,
      Long runId,
      SessionEntryType type,
      SessionEntryPayload payload,
      LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(id);
    entry.setSessionId(sessionId);
    entry.setParentEntryId(parentEntryId);
    entry.setRunId(runId);
    entry.setEntryType(type.value());
    entry.setPayloadJson(entryCodec.encode(payload));
    entry.setCreateTime(timestamp);
    if (entryMapper.insert(entry) != 1) {
      throw new IllegalStateException("cannot create child session entry");
    }
  }

  private void insertQueuedRun(
      long id, long sessionId, long triggerEntryId, LocalDateTime timestamp) {
    HarnessRunDO run = new HarnessRunDO();
    run.setId(id);
    run.setSessionId(sessionId);
    run.setTriggerEntryId(triggerEntryId);
    run.setStatus(RunStatus.QUEUED.name());
    run.setTurnIndex(0);
    run.setAttempt(0);
    run.setEventSequence(0L);
    run.setNextAttemptAt(timestamp);
    run.setCreateTime(timestamp);
    run.setUpdateTime(timestamp);
    if (runMapper.insert(run) != 1) {
      throw new IllegalStateException("cannot create child run");
    }
  }

  private void insertTask(
      long parentInvocationId,
      long parentSessionId,
      long childSessionId,
      long childRunId,
      String targetAgent,
      WorkspacePolicy workspacePolicy,
      TaskPolicy policy,
      LocalDateTime timestamp) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentSessionId);
    task.setChildSessionId(childSessionId);
    task.setChildRunId(childRunId);
    task.setTargetAgent(targetAgent);
    task.setWorkspacePolicy(workspacePolicy.name());
    task.setMaxTurns(policy.maxTurns());
    task.setIdleTimeoutMillis(
        policy.idleTimeout() == null ? null : policy.idleTimeout().toMillis());
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(timestamp);
    task.setUpdateTime(timestamp);
    if (taskMapper.insert(task) != 1) {
      throw new IllegalStateException("cannot create subagent task relation");
    }
  }

  private void requestChildCancellation(long runId, LocalDateTime timestamp) {
    runMapper.requestCancel(runId, timestamp);
    invocationMapper.requestCancelByRun(runId, timestamp);
  }

  private TaskReport report(HarnessSubagentTaskDO task, HarnessRunDO childRun, TaskState terminal) {
    String finalReport = finalAssistantReport(task.getChildSessionId());
    return new TaskReport(
        task.getChildSessionId(),
        task.getChildRunId(),
        terminal,
        finalReport,
        List.of(),
        childRun.getTurnIndex(),
        runMapper.countToolInvocations(childRun.getId()),
        null);
  }

  private String finalAssistantReport(long sessionId) {
    HarnessSessionDO session = requireSession(sessionId);
    Long entryId = session.getLeafEntryId();
    while (entryId != null) {
      HarnessSessionEntryDO entry = entryMapper.find(sessionId, entryId);
      if (entry == null) {
        throw new IllegalStateException("child session entry path is broken");
      }
      if (SessionEntryType.MESSAGE.value().equals(entry.getEntryType())) {
        MessageEntryPayload payload =
            (MessageEntryPayload)
                entryCodec.decode(SessionEntryType.MESSAGE, entry.getPayloadJson());
        if (payload.message().role() == AgentMessageRole.ASSISTANT) {
          return payload.message().contents().stream()
              .filter(TextMessageContent.class::isInstance)
              .map(TextMessageContent.class::cast)
              .map(TextMessageContent::text)
              .findFirst()
              .orElse("");
        }
      }
      entryId = entry.getParentEntryId();
    }
    return "";
  }

  private TaskInspection inspection(HarnessSubagentTaskDO task) {
    TaskState state = TaskState.valueOf(task.getStatus());
    TaskReport report = state.terminal() ? decodeReport(task.getReportJson()) : null;
    return new TaskInspection(toTask(task), report);
  }

  private SubagentTask toTask(HarnessSubagentTaskDO task) {
    return new SubagentTask(
        task.getParentInvocationId(),
        task.getParentSessionId(),
        task.getChildSessionId(),
        task.getChildRunId(),
        task.getTargetAgent(),
        WorkspacePolicy.valueOf(task.getWorkspacePolicy()),
        task.getMaxTurns(),
        task.getIdleTimeoutMillis() == null ? null : Duration.ofMillis(task.getIdleTimeoutMillis()),
        TaskState.valueOf(task.getStatus()),
        task.getReportJson(),
        instant(task.getCreateTime()),
        instant(task.getUpdateTime()));
  }

  private String encode(TaskReport report) {
    try {
      return OBJECT_MAPPER.writeValueAsString(report);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot persist task report", error);
    }
  }

  private TaskReport decodeReport(String json) {
    try {
      return OBJECT_MAPPER.readValue(json, TaskReport.class);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("malformed durable task report", error);
    }
  }

  private Object[] taskPayload(
      long childSessionId,
      long childRunId,
      String targetAgent,
      WorkspacePolicy workspacePolicy,
      HarnessRunDO parentRun) {
    return new Object[] {
      "childSessionId",
      childSessionId,
      "childRunId",
      childRunId,
      "targetAgent",
      targetAgent,
      "workspacePolicy",
      workspacePolicy.name(),
      "attempt",
      parentRun.getAttempt(),
      "turnIndex",
      parentRun.getTurnIndex()
    };
  }

  private void appendEvent(
      HarnessRunDO run, RunEventType type, Object[] fields, LocalDateTime timestamp) {
    appendEvent(run, type, RunEventPayloads.of(fields), timestamp);
  }

  private void appendEvent(
      HarnessRunDO run, RunEventType type, String payload, LocalDateTime timestamp) {
    long next = run.getEventSequence() + 1;
    if (runMapper.updateEventSequence(run.getId(), run.getEventSequence(), next, timestamp) != 1) {
      throw new IllegalStateException("cannot allocate task event sequence");
    }
    HarnessRunEventDO event = new HarnessRunEventDO();
    event.setId(runIds.newRunEventId());
    event.setRunId(run.getId());
    event.setSequence(next);
    event.setEventType(type.value());
    event.setPayloadJson(payload);
    event.setCreateTime(timestamp);
    if (eventMapper.insert(event) != 1) {
      throw new IllegalStateException("cannot append task event");
    }
    run.setEventSequence(next);
  }

  private HarnessRunDO requireRun(long runId) {
    HarnessRunDO run = runMapper.find(runId);
    if (run == null) {
      throw new IllegalStateException("unknown run: " + runId);
    }
    return run;
  }

  private HarnessSubagentTaskDO requireTask(long invocationId) {
    HarnessSubagentTaskDO task = taskMapper.find(invocationId);
    if (task == null) {
      throw new IllegalStateException("task relation was not persisted");
    }
    return task;
  }

  private HarnessSessionDO requireSession(long sessionId) {
    HarnessSessionDO session = sessionMapper.find(sessionId);
    if (session == null) {
      throw new IllegalStateException("unknown session: " + sessionId);
    }
    return session;
  }

  private HarnessSessionDO requireSessionForUpdate(long sessionId) {
    HarnessSessionDO session = sessionMapper.findForUpdate(sessionId);
    if (session == null) {
      throw new IllegalStateException("unknown session: " + sessionId);
    }
    return session;
  }

  private static boolean isTerminal(HarnessRunDO run) {
    return RunStatus.valueOf(run.getStatus()).terminal();
  }

  private static TaskState taskState(String runStatus) {
    return switch (RunStatus.valueOf(runStatus)) {
      case SUCCEEDED -> TaskState.SUCCEEDED;
      case FAILED -> TaskState.FAILED;
      case CANCELLED -> TaskState.CANCELLED;
      default -> throw new IllegalArgumentException("child run is not terminal");
    };
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }
}
