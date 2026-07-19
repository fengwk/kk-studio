package fun.fengwk.kkstudio.core.harness.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.HarnessAgentSnapshotResolver;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.task.SubagentTask;
import fun.fengwk.kkstudio.harness.runtime.task.TaskCommand;
import fun.fengwk.kkstudio.harness.runtime.task.TaskInspection;
import fun.fengwk.kkstudio.harness.runtime.task.TaskPolicy;
import fun.fengwk.kkstudio.harness.runtime.task.TaskPolicyCodec;
import fun.fengwk.kkstudio.harness.runtime.task.TaskReport;
import fun.fengwk.kkstudio.harness.runtime.task.TaskResultFormatter;
import fun.fengwk.kkstudio.harness.runtime.task.TaskRuntime;
import fun.fengwk.kkstudio.harness.runtime.task.TaskState;
import fun.fengwk.kkstudio.harness.runtime.task.WorkingCopyPolicy;
import fun.fengwk.kkstudio.harness.runtime.task.WorkingCopyRevisionResolver;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Durable subagent task runtime on AgentThread: parent invocation creates child Session + Thread +
 * initial USER input; child completion (idle + no pending work) resumes parent via kick.
 */
@Service
public class DatabaseTaskRuntime implements TaskRuntime {
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final ToolInvocationMapper invocationMapper;
  private final HarnessSubagentTaskMapper taskMapper;
  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadEventMapper threadEventMapper;
  private final ThreadStore threadStore;
  private final ThreadInputStore inputStore;
  private final ThreadEventStore eventStore;
  private final ThreadIdGenerator threadIds;
  private final AgentDefinitionMapper agentMapper;
  private final WorkingCopyRevisionResolver workingCopyRevisionResolver;
  private final HarnessAgentSnapshotResolver snapshotResolver;
  private final ThreadKick threadKick;
  private final SessionEntryJsonCodec entryCodec = new SessionEntryJsonCodec();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DatabaseTaskRuntime(
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      ToolInvocationMapper invocationMapper,
      HarnessSubagentTaskMapper taskMapper,
      HarnessThreadMapper threadMapper,
      HarnessThreadEventMapper threadEventMapper,
      ThreadStore threadStore,
      ThreadInputStore inputStore,
      ThreadEventStore eventStore,
      ThreadIdGenerator threadIds,
      AgentDefinitionMapper agentMapper,
      WorkingCopyRevisionResolver workingCopyRevisionResolver,
      HarnessAgentSnapshotResolver snapshotResolver,
      @Lazy ThreadKick threadKick) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.threadEventMapper = Objects.requireNonNull(threadEventMapper, "threadEventMapper");
    this.threadStore = Objects.requireNonNull(threadStore, "threadStore");
    this.inputStore = Objects.requireNonNull(inputStore, "inputStore");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.threadIds = Objects.requireNonNull(threadIds, "threadIds");
    this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
    this.workingCopyRevisionResolver =
        Objects.requireNonNull(workingCopyRevisionResolver, "workingCopyRevisionResolver");
    this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
  }

  @Override
  @Transactional
  public TaskInspection startOrResume(
      ToolExecutionContext context, TaskCommand command, Instant now) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(command, "command");
    LocalDateTime timestamp = utc(now);
    // 锁序：Thread → invocation（context 已带 threadId）。
    HarnessThreadDO parentThread = requireThread(context.threadId());
    ToolInvocationDO invocation = requireTaskInvocation(context);
    HarnessSessionDO parent = requireSessionForUpdate(parentThread.getSessionId());
    HarnessSessionDO root = lockRoot(parent);

    HarnessSubagentTaskDO replay = taskMapper.findForUpdate(context.invocationId());
    if (replay != null) {
      return inspection(replay);
    }

    AgentSnapshot parentSnapshot =
        snapshotResolver.snapshotOnPath(parent.getId(), parentThread.getHeadEntryId());
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
        && taskMapper.countActiveRoot(root.getId()) >= parentPolicy.maxTotal()) {
      throw new IllegalStateException("subagent root concurrency limit exceeded");
    }

    AgentDefinitionDO target = agentMapper.getByName(command.subagentType());
    if (target == null) {
      throw new IllegalArgumentException("unknown subagent: " + command.subagentType());
    }
    return createChild(context, command, parentThread, parent, root, target, timestamp, now);
  }

  @Override
  @Transactional
  public TaskInspection inspect(long parentInvocationId, Instant now) {
    HarnessSubagentTaskDO task = taskMapper.findForUpdate(parentInvocationId);
    if (task == null) {
      throw new IllegalArgumentException("unknown subagent task: " + parentInvocationId);
    }
    if (!TaskState.RUNNING.name().equals(task.getStatus())) {
      return inspection(task);
    }
    HarnessThreadDO child = threadMapper.find(task.getChildThreadId());
    if (child == null) {
      throw new IllegalStateException("child thread missing for task " + parentInvocationId);
    }
    LocalDateTime timestamp = utc(now);
    // 失败与 idle 终态都要求 child 当前 durable 不活跃；活跃/pending 重试不得用旧 lifecycle 终态化。
    if (!isChildIdle(child, now)) {
      return inspection(task);
    }
    String latestLifecycle = latestLifecycleType(child.getId());
    boolean failed = ThreadEventType.THREAD_FAILED.value().equals(latestLifecycle);
    boolean idle = ThreadEventType.THREAD_IDLE.value().equals(latestLifecycle);
    if (!failed && !idle) {
      return inspection(task);
    }
    ChildCompletion completion = evaluateChildCompletion(task, child, failed);
    if (taskMapper.complete(
            task.getParentInvocationId(),
            completion.state().name(),
            encodeReport(completion.report()),
            timestamp)
        == 1) {
      eventStore.append(
          task.getParentThreadId(),
          null,
          ThreadEventType.SUBAGENT_COMPLETED,
          ThreadEventPayloads.of(
              "childSessionId",
              Long.toString(task.getChildSessionId()),
              "childThreadId",
              Long.toString(task.getChildThreadId()),
              "status",
              completion.state().name()),
          now);
      afterCommitKick(task.getParentThreadId());
    }
    return inspection(taskMapper.find(parentInvocationId));
  }

  @Override
  @Transactional
  public void cancelTree(long parentInvocationId, Instant now) {
    // Lock order / linearization with beginTurn:
    // nonlocking task peek -> lock child Thread -> lock task + revalidate RUNNING.
    // beginTurn locks Thread then task (via admission FOR UPDATE), so the two paths serialize on
    // the child Thread row. If beginTurn wins, that admitted in-flight Turn may finish; if cancel
    // wins, beginTurn observes CANCELLED and rejects. Never admits after CANCELLED commits.
    HarnessSubagentTaskDO peek = taskMapper.find(parentInvocationId);
    if (peek == null || !TaskState.RUNNING.name().equals(peek.getStatus())) {
      return;
    }
    long childThreadId = peek.getChildThreadId();
    threadMapper.findForUpdate(childThreadId);
    HarnessSubagentTaskDO task = taskMapper.findForUpdate(parentInvocationId);
    if (task == null || !TaskState.RUNNING.name().equals(task.getStatus())) {
      return;
    }
    LocalDateTime timestamp = utc(now);
    TaskReport report =
        new TaskReport(
            task.getChildSessionId(),
            task.getChildThreadId(),
            TaskState.CANCELLED,
            "cancelled",
            List.of(),
            0,
            0,
            WorkingCopyPolicy.valueOf(task.getWorkingCopyPolicy()),
            task.getWorkingCopyRevision());
    if (taskMapper.complete(
            parentInvocationId, TaskState.CANCELLED.name(), encodeReport(report), timestamp)
        == 1) {
      // Request cancel on nonterminal child tools. Already-admitted in-flight LLM work is not
      // interrupted; subsequent beginTurn rejects on CANCELLED.
      invocationMapper.requestCancelByThread(task.getChildThreadId(), timestamp);
      eventStore.append(
          task.getParentThreadId(),
          null,
          ThreadEventType.SUBAGENT_CANCEL_REQUESTED,
          ThreadEventPayloads.of("childThreadId", Long.toString(task.getChildThreadId())),
          now);
      afterCommitKick(task.getParentThreadId());
      afterCommitKick(task.getChildThreadId());
    }
  }

  private TaskInspection createChild(
      ToolExecutionContext context,
      TaskCommand command,
      HarnessThreadDO parentThread,
      HarnessSessionDO parent,
      HarnessSessionDO root,
      AgentDefinitionDO target,
      LocalDateTime timestamp,
      Instant now) {
    long childSessionId = AgentIdGenerator.nextHarnessSessionId();
    long snapshotEntryId = threadIds.newSessionEntryId();
    long childThreadId = threadIds.newThreadId();
    AgentSnapshot targetSnapshot = snapshotResolver.snapshotForDefinition(target);
    HarnessSessionDO child = new HarnessSessionDO();
    child.setId(childSessionId);
    child.setTitle(target.getName());
    child.setMainThreadId(childThreadId);
    child.setParentSessionId(parent.getId());
    child.setRootSessionId(root.getId());
    child.setParentInvocationId(context.invocationId());
    child.setDepth(parent.getDepth() + 1);
    child.setVersion(0L);
    child.setCreateTime(timestamp);
    child.setUpdateTime(timestamp);
    sessionMapper.insert(child);

    insertEntry(
        snapshotEntryId,
        childSessionId,
        null,
        SessionEntryType.AGENT_SNAPSHOT,
        new AgentSnapshotEntryPayload(target.getId(), targetSnapshot),
        timestamp);
    AgentThread childThread =
        new AgentThread(
            childThreadId,
            childSessionId,
            snapshotEntryId,
            ThreadStatus.RUNNING,
            1L,
            null,
            null,
            0L,
            now,
            now);
    threadStore.create(childThread);
    inputStore.insert(
        new ThreadInput(
            threadIds.newThreadInputId(),
            childThreadId,
            1L,
            ThreadInputType.USER_MESSAGE,
            entryCodec.encode(
                new MessageEntryPayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent(command.prompt()))))),
            "subagent:" + context.invocationId(),
            ThreadInputStatus.QUEUED,
            null,
            null,
            null,
            now));

    TaskPolicy targetPolicy = TaskPolicyCodec.decode(targetSnapshot.executionPolicyJson());
    String workingCopyRevision =
        workingCopyRevisionResolver
            .resolve(command.workingCopyPolicy(), childSessionId)
            .orElse(null);
    HarnessSubagentTaskDO task = new HarnessSubagentTaskDO();
    task.setParentInvocationId(context.invocationId());
    task.setParentSessionId(parent.getId());
    task.setParentThreadId(parentThread.getId());
    HarnessSubagentTaskDO parentTask = taskMapper.findByChildThreadId(parentThread.getId());
    task.setRootThreadId(parentTask == null ? parentThread.getId() : parentTask.getRootThreadId());
    task.setChildSessionId(childSessionId);
    task.setChildThreadId(childThreadId);
    task.setTargetAgent(target.getName());
    task.setWorkingCopyPolicy(command.workingCopyPolicy().name());
    task.setWorkingCopyRevision(workingCopyRevision);
    task.setMaxTurns(targetPolicy.maxTurns());
    task.setStatus(TaskState.RUNNING.name());
    task.setCreateTime(timestamp);
    task.setUpdateTime(timestamp);
    taskMapper.insert(task);

    eventStore.append(
        parentThread.getId(),
        null,
        ThreadEventType.SUBAGENT_STARTED,
        ThreadEventPayloads.of(
            "childSessionId",
            Long.toString(childSessionId),
            "childThreadId",
            Long.toString(childThreadId),
            "target",
            target.getName()),
        now);
    eventStore.append(
        childThreadId,
        snapshotEntryId,
        ThreadEventType.THREAD_STARTED,
        ThreadEventPayloads.of("parentInvocationId", Long.toString(context.invocationId())),
        now);
    afterCommitKick(childThreadId);
    return inspection(task);
  }

  private boolean isChildIdle(HarnessThreadDO child, Instant now) {
    LocalDateTime clock = utc(now);
    if (child.getProcessorToken() != null
        && child.getProcessorUntil() != null
        && child.getProcessorUntil().isAfter(clock)) {
      return false;
    }
    if (invocationMapper.countNonTerminalByThread(child.getId()) != 0) {
      return false;
    }
    return inputStore.listQueued(child.getId()).isEmpty();
  }

  private ChildCompletion evaluateChildCompletion(
      HarnessSubagentTaskDO task, HarnessThreadDO child, boolean failedLifecycle) {
    int toolCount = invocationMapper.listByThread(child.getId()).size();
    int turnCount =
        threadEventMapper.countByType(child.getId(), ThreadEventType.TURN_STARTED.value());
    WorkingCopyPolicy policy = WorkingCopyPolicy.valueOf(task.getWorkingCopyPolicy());
    if (failedLifecycle) {
      String errorText = lastFailureMessage(child.getId());
      TaskReport report =
          new TaskReport(
              task.getChildSessionId(),
              task.getChildThreadId(),
              TaskState.FAILED,
              errorText,
              List.of(),
              turnCount,
              toolCount,
              policy,
              task.getWorkingCopyRevision());
      return new ChildCompletion(TaskState.FAILED, report);
    }
    HarnessSessionEntryDO head = entryMapper.find(child.getSessionId(), child.getHeadEntryId());
    String finalText = "";
    boolean finalAssistant = false;
    if (head != null && SessionEntryType.MESSAGE.value().equals(head.getEntryType())) {
      SessionEntryPayload payload =
          entryCodec.decode(SessionEntryType.MESSAGE, head.getPayloadJson());
      if (payload instanceof MessageEntryPayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        finalAssistant = true;
        finalText =
            message.message().contents().stream()
                .filter(TextMessageContent.class::isInstance)
                .map(c -> ((TextMessageContent) c).text())
                .findFirst()
                .orElse("");
      }
    }
    if (!finalAssistant) {
      String errorText =
          finalText.isBlank() ? "child thread completed without final assistant head" : finalText;
      TaskReport report =
          new TaskReport(
              task.getChildSessionId(),
              task.getChildThreadId(),
              TaskState.FAILED,
              errorText,
              List.of(),
              turnCount,
              toolCount,
              policy,
              task.getWorkingCopyRevision());
      return new ChildCompletion(TaskState.FAILED, report);
    }
    TaskReport report =
        new TaskReport(
            task.getChildSessionId(),
            task.getChildThreadId(),
            TaskState.SUCCEEDED,
            finalText,
            List.of(),
            turnCount,
            toolCount,
            policy,
            task.getWorkingCopyRevision());
    return new ChildCompletion(TaskState.SUCCEEDED, report);
  }

  private String latestLifecycleType(long childThreadId) {
    HarnessThreadEventDO latest = threadEventMapper.findLatestLifecycle(childThreadId);
    return latest == null ? null : latest.getEventType();
  }

  private String lastFailureMessage(long childThreadId) {
    HarnessThreadEventDO failure = threadEventMapper.findLatestFailure(childThreadId);
    if (failure == null || failure.getPayloadJson() == null) {
      return "child thread failed";
    }
    try {
      var node = objectMapper.readTree(failure.getPayloadJson());
      if (node.hasNonNull("message")) {
        return node.get("message").asText();
      }
      if (node.hasNonNull("reason")) {
        return node.get("reason").asText();
      }
    } catch (JsonProcessingException ignored) {
      // fall through
    }
    return "child thread failed";
  }

  private TaskInspection inspection(HarnessSubagentTaskDO task) {
    if (task == null) {
      throw new IllegalStateException("task missing");
    }
    SubagentTask domain =
        new SubagentTask(
            task.getParentInvocationId(),
            task.getParentSessionId(),
            task.getParentThreadId(),
            task.getRootThreadId(),
            task.getChildSessionId(),
            task.getChildThreadId(),
            task.getTargetAgent(),
            WorkingCopyPolicy.valueOf(task.getWorkingCopyPolicy()),
            task.getWorkingCopyRevision(),
            task.getMaxTurns(),
            TaskState.valueOf(task.getStatus()),
            task.getReportJson(),
            task.getCreateTime().toInstant(ZoneOffset.UTC),
            task.getUpdateTime().toInstant(ZoneOffset.UTC));
    TaskReport report = null;
    if (task.getReportJson() != null && !task.getReportJson().isBlank()) {
      report = TaskResultFormatter.decodeJson(task.getReportJson());
    }
    return new TaskInspection(domain, report);
  }

  private record ChildCompletion(TaskState state, TaskReport report) {}

  private ToolInvocationDO requireTaskInvocation(ToolExecutionContext context) {
    // 调用方已持有 Thread 行锁；此处再锁 invocation 并校验归属。
    ToolInvocationDO invocation = invocationMapper.findForUpdate(context.invocationId());
    if (invocation == null) {
      throw new IllegalStateException("parent task invocation missing");
    }
    if (!Objects.equals(invocation.getThreadId(), context.threadId())) {
      throw new IllegalStateException("invocation thread mismatch");
    }
    return invocation;
  }

  private HarnessThreadDO requireThread(long threadId) {
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null) {
      throw new IllegalStateException("unknown thread: " + threadId);
    }
    return thread;
  }

  private HarnessSessionDO requireSessionForUpdate(long sessionId) {
    HarnessSessionDO session = sessionMapper.findForUpdate(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    return session;
  }

  private HarnessSessionDO lockRoot(HarnessSessionDO session) {
    if (Objects.equals(session.getRootSessionId(), session.getId())) {
      return session;
    }
    return requireSessionForUpdate(session.getRootSessionId());
  }

  private void insertEntry(
      long id,
      long sessionId,
      Long parentEntryId,
      SessionEntryType type,
      SessionEntryPayload payload,
      LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(id);
    entry.setSessionId(sessionId);
    entry.setParentEntryId(parentEntryId);
    entry.setEntryType(type.value());
    entry.setPayloadJson(entryCodec.encode(payload));
    entry.setCreateTime(timestamp);
    entryMapper.insert(entry);
  }

  private void afterCommitKick(long threadId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      threadKick.kick(threadId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            threadKick.kick(threadId);
          }
        });
  }

  private String encodeSnapshotJson(AgentSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode agent snapshot", error);
    }
  }

  private static String encodeReport(TaskReport report) {
    return TaskResultFormatter.json(report);
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
