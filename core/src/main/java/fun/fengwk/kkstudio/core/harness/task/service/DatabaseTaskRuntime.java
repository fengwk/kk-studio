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
import fun.fengwk.kkstudio.harness.runtime.task.TaskPolicyCodec;
import fun.fengwk.kkstudio.harness.runtime.task.TaskReport;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspacePolicy;
import fun.fengwk.kkstudio.harness.runtime.task.WorkspaceRevisionResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
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
  private final WorkspaceRevisionResolver workspaceRevisionResolver;
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
      SessionIdGenerator sessionIds,
      WorkspaceRevisionResolver workspaceRevisionResolver) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
    this.runIds = Objects.requireNonNull(runIds, "runIds");
    this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
    this.workspaceRevisionResolver =
        Objects.requireNonNull(workspaceRevisionResolver, "workspaceRevisionResolver");
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
    TaskPolicy parentPolicy = TaskPolicyCodec.decode(parentSnapshot.executionPolicyJson());
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

    if (command.sessionId() != null) {
      return resumeChild(context, command, parentRun, parent, root, timestamp);
    }
    AgentDefinitionDO target =
        agentMapper.getByWorkspaceIdAndName(parent.getWorkspaceId(), command.subagentType());
    if (target == null) {
      throw new IllegalArgumentException(
          "unknown subagent in workspace: " + command.subagentType());
    }
    return createChild(context, command, parentRun, parent, root, target, timestamp);
  }

  @Override
  @Transactional
  public TaskInspection inspect(long parentInvocationId, Instant now) {
    LockedTaskParent locked = lockTaskParent(parentInvocationId);
    HarnessSubagentTaskDO task = locked.task();
    if (!TaskState.RUNNING.name().equals(task.getStatus())) {
      return inspection(task);
    }
    HarnessRunDO childRun = runMapper.findForUpdate(task.getChildRunId());
    if (childRun == null) {
      throw new IllegalStateException("task child run is missing");
    }
    LocalDateTime timestamp = utc(now);
    if (!isTerminal(childRun)) {
      String cancellationReason = cancellationReason(task, childRun, timestamp);
      if (cancellationReason != null && requestChildCancellation(task.getChildRunId(), timestamp)) {
        appendEvent(
            locked.parentRun(),
            RunEventType.SUBAGENT_CANCEL_REQUESTED,
            RunEventPayloads.of(
                "childSessionId", task.getChildSessionId(),
                "childRunId", task.getChildRunId(),
                "reason", cancellationReason,
                "attempt", locked.parentRun().getAttempt(),
                "turnIndex", locked.parentRun().getTurnIndex()),
            timestamp);
      }
      return inspection(task);
    }

    TaskState terminal = taskState(childRun.getStatus());
    TaskReport report = report(task, childRun, terminal);
    if (taskMapper.complete(
            task.getParentInvocationId(), terminal.name(), encode(report), timestamp)
        == 1) {
      appendEvent(
          locked.parentRun(),
          RunEventType.SUBAGENT_COMPLETED,
          RunEventPayloads.of(
              "childSessionId", task.getChildSessionId(),
              "childRunId", task.getChildRunId(),
              "status", terminal.name(),
              "attempt", locked.parentRun().getAttempt(),
              "turnIndex", locked.parentRun().getTurnIndex()),
          timestamp);
    }
    return inspection(requireTask(parentInvocationId));
  }

  @Override
  @Transactional
  public void cancelTree(long parentInvocationId, Instant now) {
    LockedTaskParent locked = lockTaskParent(parentInvocationId);
    LocalDateTime timestamp = utc(now);
    ArrayDeque<Long> pending = new ArrayDeque<>();
    HashSet<Long> visitedSessions = new HashSet<>();
    pending.add(locked.task().getChildSessionId());
    boolean requested = false;
    while (!pending.isEmpty()) {
      long childSessionId = pending.removeFirst();
      if (!visitedSessions.add(childSessionId)) {
        continue;
      }
      HarnessSessionDO child = requireSessionForUpdate(childSessionId);
      if (child.getActiveRunId() != null) {
        requested |= requestChildCancellation(child.getActiveRunId(), timestamp);
      }
      taskMapper.listByParentSession(childSessionId).stream()
          .map(HarnessSubagentTaskDO::getChildSessionId)
          .distinct()
          .sorted()
          .forEach(pending::addLast);
    }
    if (requested) {
      appendEvent(
          locked.parentRun(),
          RunEventType.SUBAGENT_CANCEL_REQUESTED,
          RunEventPayloads.of(
              "childSessionId", locked.task().getChildSessionId(),
              "childRunId", locked.task().getChildRunId(),
              "reason", "parent_cancelled",
              "attempt", locked.parentRun().getAttempt(),
              "turnIndex", locked.parentRun().getTurnIndex()),
          timestamp);
    }
  }

  private TaskInspection createChild(
      ToolExecutionContext context,
      TaskCommand command,
      HarnessRunDO parentRun,
      HarnessSessionDO parent,
      HarnessSessionDO root,
      AgentDefinitionDO target,
      LocalDateTime timestamp) {
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
    TaskPolicy targetPolicy = TaskPolicyCodec.decode(targetSnapshot.executionPolicyJson());
    String workspaceRevision =
        workspaceRevisionResolver
            .resolve(parent.getWorkspaceId(), command.workspacePolicy(), childSessionId)
            .orElse(null);
    insertTask(
        context.invocationId(),
        parent.getId(),
        childSessionId,
        childRunId,
        target.getName(),
        command.workspacePolicy(),
        workspaceRevision,
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
      LocalDateTime timestamp) {
    HarnessSessionDO child = requireSessionForUpdate(command.sessionId());
    if (!Objects.equals(child.getWorkspaceId(), parent.getWorkspaceId())
        || !Objects.equals(child.getRootSessionId(), root.getId())
        || !Objects.equals(child.getParentSessionId(), parent.getId())
        || child.getParentInvocationId() == null) {
      throw new IllegalArgumentException("resume child does not match parent hierarchy");
    }
    HarnessSubagentTaskDO creation = requireTask(child.getParentInvocationId());
    if (!Objects.equals(creation.getChildSessionId(), child.getId())
        || !Objects.equals(creation.getParentSessionId(), parent.getId())
        || !command.subagentType().equals(creation.getTargetAgent())) {
      throw new IllegalArgumentException(
          "resume child does not match its frozen creation task relation");
    }
    WorkspacePolicy workspacePolicy = WorkspacePolicy.valueOf(creation.getWorkspacePolicy());
    if (command.workspacePolicy() != workspacePolicy) {
      throw new IllegalArgumentException("resume workspace policy differs from child creation");
    }
    AgentSnapshot targetSnapshot = childSnapshot(child);
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
    TaskPolicy targetPolicy = TaskPolicyCodec.decode(targetSnapshot.executionPolicyJson());
    insertTask(
        context.invocationId(),
        parent.getId(),
        child.getId(),
        childRunId,
        creation.getTargetAgent(),
        workspacePolicy,
        creation.getWorkspaceRevision(),
        targetPolicy,
        timestamp);
    appendEvent(
        parentRun,
        RunEventType.SUBAGENT_RESUMED,
        taskPayload(
            child.getId(), childRunId, creation.getTargetAgent(), workspacePolicy, parentRun),
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
    return snapshotOnCurrentPath(requireSession(sessionId));
  }

  private AgentSnapshot childSnapshot(HarnessSessionDO session) {
    return snapshotOnCurrentPath(session);
  }

  private AgentSnapshot snapshotOnCurrentPath(HarnessSessionDO session) {
    if (session.getLeafEntryId() == null) {
      throw new IllegalStateException("session has no current leaf for agent snapshot lookup");
    }
    HarnessSessionEntryDO entry =
        entryMapper.findLatestOnPathByType(
            session.getId(), session.getLeafEntryId(), SessionEntryType.AGENT_SNAPSHOT.value());
    if (entry == null) {
      throw new IllegalStateException("session has no frozen agent snapshot on its current path");
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
      String workspaceRevision,
      TaskPolicy policy,
      LocalDateTime timestamp) {
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(parentInvocationId);
    task.setParentSessionId(parentSessionId);
    task.setChildSessionId(childSessionId);
    task.setChildRunId(childRunId);
    task.setTargetAgent(targetAgent);
    task.setWorkspacePolicy(workspacePolicy.name());
    task.setWorkspaceRevision(workspaceRevision);
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

  private boolean requestChildCancellation(long runId, LocalDateTime timestamp) {
    boolean requested = runMapper.requestCancel(runId, timestamp) == 1;
    if (requested) {
      invocationMapper.requestCancelByRun(runId, timestamp);
    }
    return requested;
  }

  private String cancellationReason(
      HarnessSubagentTaskDO task, HarnessRunDO childRun, LocalDateTime now) {
    if (childRun.getTurnIndex() >= task.getMaxTurns()) {
      return "max_turns";
    }
    if (task.getIdleTimeoutMillis() == null) {
      return null;
    }
    LocalDateTime activity = eventMapper.latestActivityAt(childRun.getId());
    if (activity == null) {
      activity = childRun.getCreateTime();
    }
    return !activity.plus(task.getIdleTimeoutMillis(), ChronoUnit.MILLIS).isAfter(now)
        ? "idle_timeout"
        : null;
  }

  /** Locks the parent aggregate first, then its task relation, before any child row. */
  private LockedTaskParent lockTaskParent(long parentInvocationId) {
    ToolInvocationDO hint = invocationMapper.find(parentInvocationId);
    if (hint == null) {
      throw new IllegalArgumentException("unknown task invocation: " + parentInvocationId);
    }
    HarnessRunDO parentRun = runMapper.findForUpdate(hint.getRunId());
    if (parentRun == null) {
      throw new IllegalStateException("task parent run is missing");
    }
    HarnessSessionDO parent = requireSessionForUpdate(parentRun.getSessionId());
    HarnessSessionDO root = lockRoot(parent);
    ToolInvocationDO invocation = invocationMapper.findForUpdate(parentInvocationId);
    if (invocation == null || !Objects.equals(invocation.getRunId(), parentRun.getId())) {
      throw new IllegalStateException("task invocation changed while locking parent");
    }
    HarnessSubagentTaskDO task = taskMapper.findForUpdate(parentInvocationId);
    if (task == null) {
      throw new IllegalArgumentException("unknown task invocation: " + parentInvocationId);
    }
    return new LockedTaskParent(parentRun, parent, root, invocation, task);
  }

  private record LockedTaskParent(
      HarnessRunDO parentRun,
      HarnessSessionDO parent,
      HarnessSessionDO root,
      ToolInvocationDO invocation,
      HarnessSubagentTaskDO task) {}

  private TaskReport report(HarnessSubagentTaskDO task, HarnessRunDO childRun, TaskState terminal) {
    return new TaskReport(
        task.getChildSessionId(),
        task.getChildRunId(),
        terminal,
        finalAssistantReport(task.getChildSessionId(), childRun.getId()),
        artifacts(childRun.getId()),
        childRun.getTurnIndex(),
        runMapper.countToolInvocations(childRun.getId()),
        WorkspacePolicy.valueOf(task.getWorkspacePolicy()),
        task.getWorkspaceRevision());
  }

  private List<ArtifactRef> artifacts(long runId) {
    LinkedHashMap<String, ArtifactRef> result = new LinkedHashMap<>();
    for (ToolInvocationDO invocation : invocationMapper.listByRun(runId)) {
      if (invocation.getResultJson() == null) {
        continue;
      }
      ToolResult toolResult = ToolResultJsonCodec.decode(invocation.getResultJson());
      toolResult.contents().stream()
          .filter(ArtifactToolContent.class::isInstance)
          .map(ArtifactToolContent.class::cast)
          .map(ArtifactToolContent::artifact)
          .forEach(artifact -> result.putIfAbsent(artifact.artifactId(), artifact));
    }
    return List.copyOf(result.values());
  }

  private String finalAssistantReport(long sessionId, long childRunId) {
    HarnessSessionDO session = requireSession(sessionId);
    List<HarnessSessionEntryDO> path = new ArrayList<>();
    Long entryId = session.getLeafEntryId();
    while (entryId != null) {
      HarnessSessionEntryDO entry = entryMapper.find(sessionId, entryId);
      if (entry == null) {
        throw new IllegalStateException("child session entry path is broken");
      }
      path.add(entry);
      entryId = entry.getParentEntryId();
    }
    Collections.reverse(path);
    return path.stream()
        .filter(entry -> Objects.equals(entry.getRunId(), childRunId))
        .filter(entry -> SessionEntryType.MESSAGE.value().equals(entry.getEntryType()))
        .map(
            entry ->
                (MessageEntryPayload)
                    entryCodec.decode(SessionEntryType.MESSAGE, entry.getPayloadJson()))
        .filter(payload -> payload.message().role() == AgentMessageRole.ASSISTANT)
        .flatMap(payload -> payload.message().contents().stream())
        .filter(TextMessageContent.class::isInstance)
        .map(TextMessageContent.class::cast)
        .map(TextMessageContent::text)
        .collect(Collectors.joining("\n"));
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
        task.getWorkspaceRevision(),
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
