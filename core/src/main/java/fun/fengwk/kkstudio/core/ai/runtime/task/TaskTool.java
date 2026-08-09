package fun.fengwk.kkstudio.core.ai.runtime.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetActiveToolsCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetThinkingLevelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 以普通 durable Harness Thread 运行隔离子 Agent 的内部 task 平台工具。 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";
  public static final String VERSION = "1";
  public static final String RENDERER_KEY = "task";

  private static final int MAX_TURNS_REMINDER_INTERVAL = 5;
  private static final int REPORT_FALLBACK_MAX_CHARS = 8_000;
  private static final long STATUS_HEARTBEAT_NANOS = Duration.ofSeconds(1).toNanos();

  private final ObjectProvider<HarnessRuntime> runtimeProvider;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfig config;
  private final SubagentRunRegistry runRegistry;
  private final ExecutorService executor;
  private final ObjectMapper objectMapper;
  private final ToolDescriptor descriptor;

  public TaskTool(
      ObjectProvider<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfig config,
      SubagentRunRegistry runRegistry,
      ExecutorService executor,
      ObjectMapper objectMapper) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.config = Objects.requireNonNull(config, "config");
    this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.descriptor =
        new ToolDescriptor(
            NAME,
            VERSION,
            ToolType.PLATFORM,
            TaskPrompts.toolDescription(config.maxTurns()),
            RENDERER_KEY,
            new ToolParamsSchema(
                "委派一个隔离、可恢复的子 Agent Session。",
                Map.of(
                    "subagent_type",
                    new ToolStringSchema("必须来自 system prompt 中 available_subagents 的 Agent 名称。"),
                    "prompt",
                    new ToolStringSchema("交给子 Agent 的完整、自洽任务说明。"),
                    "maxTurns",
                    new ToolIntegerSchema("可选正数软回合预算；达到后要求子 Agent 返回阶段报告。"),
                    "session_id",
                    new ToolStringSchema("可选的既有 task id；提供时恢复该子 Agent Session。")),
                Set.of("subagent_type", "prompt"),
                false),
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ZERO);
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (request.context() == null) {
      throw new IllegalArgumentException("task requires durable ToolExecutionContext");
    }
    TaskExecution execution = new TaskExecution(request, listener);
    executor.execute(execution::run);
    return execution;
  }

  private final class TaskExecution implements ToolExecutionHandle {
    private final ToolExecutionRequest request;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong childThreadId = new AtomicLong();

    private TaskExecution(ToolExecutionRequest request, ToolExecutionListener listener) {
      this.request = request;
      this.listener = listener;
    }

    private void run() {
      ToolCall call = request.call();
      try {
        Arguments arguments = parseArguments(call);
        HarnessRuntime runtime = requireRuntime();
        ParentContext parent = parentContext(runtime, request);
        SubagentBinding selected =
            parent.allowedSubagents().stream()
                .filter(binding -> binding.name().equals(arguments.subagentType()))
                .findFirst()
                .orElseThrow(
                    () ->
                        reject(
                            "subagent_type \""
                                + arguments.subagentType()
                                + "\" is not allowed; available: "
                                + availableNames(parent.allowedSubagents())));
        if (cancelled.get()) {
          complete(
              call,
              arguments.subagentType(),
              0L,
              RunState.CANCELLED,
              "Cancelled before the subagent session started.");
          return;
        }
        long resumeThreadId = arguments.sessionId() == null ? 0L : arguments.sessionId();
        try (SubagentRunRegistry.Reservation reservation =
            reserve(parent, resumeThreadId == 0 ? null : resumeThreadId)) {
          ThreadSnapshot child =
              resumeThreadId == 0
                  ? createChild(runtime, parent, selected.name(), request.context().invocationId())
                  : resumeChild(runtime, parent, selected.name(), resumeThreadId);
          long threadId = child.thread().id();
          childThreadId.set(threadId);
          reservation.attach(threadId);
          if (cancelled.get()) {
            cancelChild(runtime, threadId, request.context().invocationId());
            complete(
                call, selected.name(), threadId, RunState.CANCELLED, cancelledMessage(threadId));
            return;
          }
          BranchSettings target =
              settingsMaterializer.materialize(
                  selected.name(),
                  child.entryPath().baseSettings().environmentName(),
                  depth(child),
                  config);
          List<NewThreadCommand> commands =
              taskCommands(
                  request.context().invocationId(),
                  child.entryPath().baseSettings(),
                  target,
                  arguments.prompt());
          long sourceHeadEntryId = child.thread().headEntryId();
          enqueue(runtime, child, commands);
          RunResult result =
              awaitResult(
                  runtime,
                  call,
                  selected.name(),
                  threadId,
                  sourceHeadEntryId,
                  arguments.maxTurns());
          complete(call, selected.name(), threadId, result.state(), result.report());
        }
      } catch (TaskRejectedException rejected) {
        complete(
            call,
            safeSubagentType(call),
            childThreadId.get(),
            RunState.ERROR,
            rejected.getMessage());
      } catch (RuntimeException failure) {
        listener.onError(failure);
      }
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      long threadId = childThreadId.get();
      if (threadId > 0) {
        try {
          cancelChild(requireRuntime(), threadId, request.context().invocationId());
        } catch (RuntimeException ignored) {
          // 父 Tool stop 已是 durable 事实；子 Thread 的 best-effort cascade 失败不能反转它。
        }
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    private void complete(
        ToolCall call, String subagentType, long threadId, RunState state, String report) {
      TaskTool.this.complete(listener, call, subagentType, threadId, state, report);
    }

    private void publishStatus(
        ToolCall call,
        String subagentType,
        ThreadSnapshot snapshot,
        long sourceHeadEntryId,
        List<SubagentRunRegistry.RelayedStatus> descendants) {
      TaskTool.this.publishStatus(
          listener, call, subagentType, snapshot, sourceHeadEntryId, descendants);
    }

    private RunResult awaitResult(
        HarnessRuntime runtime,
        ToolCall call,
        String subagentType,
        long threadId,
        long sourceHeadEntryId,
        int maxTurns) {
      int nextReminderTurn = maxTurns;
      String previousFingerprint = "";
      List<SubagentRunRegistry.RelayedStatus> previousDescendants = List.of();
      long lastActivityNanos = System.nanoTime();
      long lastStatusNanos = lastActivityNanos;
      while (true) {
        if (cancelled.get()) {
          cancelChild(runtime, threadId, request.context().invocationId());
          return new RunResult(RunState.CANCELLED, cancelledMessage(threadId));
        }
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
        String fingerprint = fingerprint(snapshot);
        List<SubagentRunRegistry.RelayedStatus> descendants =
            runRegistry.descendantStatuses(threadId);
        long now = System.nanoTime();
        boolean durableChanged = !fingerprint.equals(previousFingerprint);
        boolean relayChanged = !descendants.equals(previousDescendants);
        if (durableChanged) {
          previousFingerprint = fingerprint;
          lastActivityNanos = now;
        }
        if (relayChanged) {
          previousDescendants = descendants;
        }
        if (durableChanged || relayChanged || now - lastStatusNanos >= STATUS_HEARTBEAT_NANOS) {
          publishStatus(call, subagentType, snapshot, sourceHeadEntryId, descendants);
          lastStatusNanos = now;
        }
        RunResult terminal = terminalResult(snapshot, sourceHeadEntryId);
        if (terminal != null) {
          return terminal;
        }
        int turns = countTurns(snapshot, sourceHeadEntryId);
        if (turns >= nextReminderTurn
            && enqueueReminder(
                runtime, snapshot, request.context().invocationId(), nextReminderTurn)) {
          nextReminderTurn = Math.addExact(nextReminderTurn, MAX_TURNS_REMINDER_INTERVAL);
        }
        if (idleTimedOut(snapshot, lastActivityNanos)) {
          cancelChild(runtime, threadId, request.context().invocationId());
          return new RunResult(
              RunState.ERROR,
              "Subagent idle timeout after "
                  + config.idleTimeout()
                  + ". Session preserved as `"
                  + threadId
                  + "`.");
        }
        sleep();
      }
    }

    private boolean idleTimedOut(ThreadSnapshot snapshot, long lastActivityNanos) {
      if (config.idleTimeout().isZero() || !snapshot.toolSiblings().isEmpty()) {
        return false;
      }
      long elapsed = System.nanoTime() - lastActivityNanos;
      return elapsed >= config.idleTimeout().toNanos();
    }

    private void sleep() {
      try {
        Thread.sleep(config.pollInterval());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("task observation thread was interrupted", interrupted);
      }
    }
  }

  private ParentContext parentContext(HarnessRuntime runtime, ToolExecutionRequest request) {
    long parentThreadId = request.context().threadId();
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(parentThreadId);
    if (snapshot.model() == null
        || snapshot.toolSiblings().stream()
            .noneMatch(tool -> tool.id() == request.context().invocationId())) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    int depth = root.subagentContext() == null ? 1 : root.subagentContext().depth();
    if (depth >= config.maxDepth()) {
      throw reject("subagent max depth reached: " + depth + "/" + config.maxDepth());
    }
    List<SubagentBinding> allowed = snapshot.model().request().subagentBindings();
    if (allowed.isEmpty()) {
      throw reject("the frozen parent invocation does not allow subagent delegation");
    }
    long rootThreadId =
        root.subagentContext() == null ? parentThreadId : root.subagentContext().rootThreadId();
    return new ParentContext(snapshot, depth, rootThreadId, allowed);
  }

  private SubagentRunRegistry.Reservation reserve(ParentContext parent, Long resumeThreadId) {
    try {
      return runRegistry.reserve(
          parent.snapshot().thread().id(), parent.rootThreadId(), resumeThreadId, config);
    } catch (IllegalArgumentException error) {
      throw reject(error.getMessage());
    }
  }

  private ThreadSnapshot createChild(
      HarnessRuntime runtime, ParentContext parent, String subagentType, long taskInvocationId) {
    int childDepth = parent.depth() + 1;
    BranchSettings settings =
        settingsMaterializer.materialize(
            subagentType,
            parent.snapshot().entryPath().baseSettings().environmentName(),
            childDepth,
            config);
    CreatedThread created =
        runtime.createThread(
            new CreateThreadCommand(
                "subagent:" + subagentType,
                settings,
                parent.snapshot().thread().yoloEnabled(),
                new SubagentContext(
                    parent.snapshot().thread().id(),
                    parent.rootThreadId(),
                    taskInvocationId,
                    childDepth)));
    return runtime.getThreadSnapshot(created.thread().id());
  }

  private ThreadSnapshot resumeChild(
      HarnessRuntime runtime, ParentContext parent, String subagentType, long threadId) {
    ThreadSnapshot snapshot;
    try {
      snapshot = runtime.getThreadSnapshot(threadId);
    } catch (HarnessRuntimeNotFoundException notFound) {
      throw reject("subagent session \"" + threadId + "\" was not found");
    }
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    SubagentContext context = root.subagentContext();
    if (context == null
        || context.parentThreadId() != parent.snapshot().thread().id()
        || context.rootThreadId() != parent.rootThreadId()) {
      throw reject("subagent session \"" + threadId + "\" does not belong to this parent");
    }
    if (snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()
        || !snapshot.queuedCommands().isEmpty()
        || (snapshot.entryPath().head().payload() instanceof TurnEndPayload end
            && end.continueModel())) {
      throw reject("subagent session \"" + threadId + "\" is not quiescent");
    }
    // 目标 Agent 可在恢复时切换；完整 settings diff 与 prompt 在同一 command batch 中提交。
    settingsMaterializer.materialize(
        subagentType,
        snapshot.entryPath().baseSettings().environmentName(),
        context.depth(),
        config);
    return snapshot;
  }

  private static List<NewThreadCommand> taskCommands(
      long invocationId, BranchSettings current, BranchSettings target, String prompt) {
    List<NewThreadCommand> commands = new ArrayList<>();
    int ordinal = 0;
    if (!current.agentName().equals(target.agentName())) {
      commands.add(
          command(new SetAgentCommandPayload(target.agentName()), invocationId, ordinal++));
    }
    if (!current.model().equals(target.model())) {
      commands.add(command(new SetModelCommandPayload(target.model()), invocationId, ordinal++));
    }
    if (!current.thinkingLevel().equals(target.thinkingLevel())) {
      commands.add(
          command(
              new SetThinkingLevelCommandPayload(target.thinkingLevel()), invocationId, ordinal++));
    }
    if (!current.activeTools().equals(target.activeTools())) {
      commands.add(
          command(new SetActiveToolsCommandPayload(target.activeTools()), invocationId, ordinal++));
    }
    commands.add(
        command(new UserMessageCommandPayload(AgentMessage.user(prompt)), invocationId, ordinal));
    return List.copyOf(commands);
  }

  private static NewThreadCommand command(
      ThreadCommandPayload payload, long invocationId, int ordinal) {
    return new NewThreadCommand(payload, "task-" + invocationId + "-" + ordinal);
  }

  private static void enqueue(
      HarnessRuntime runtime, ThreadSnapshot snapshot, List<NewThreadCommand> commands) {
    try {
      runtime.enqueueCommands(
          new ThreadCommandBatch(
              snapshot.thread().id(),
              snapshot.thread().headEntryId(),
              snapshot.thread().nextCommandSequence(),
              commands));
    } catch (HarnessRuntimeConflictException conflict) {
      throw reject("subagent session changed before the task prompt could be queued");
    }
  }

  private boolean enqueueReminder(
      HarnessRuntime runtime, ThreadSnapshot snapshot, long invocationId, int turn) {
    if (snapshot.model() == null && snapshot.toolSiblings().isEmpty()) {
      return false;
    }
    NewThreadCommand reminder =
        new NewThreadCommand(
            new CustomMessageCommandPayload(AgentMessage.system(TaskPrompts.maxTurnsReminder())),
            "task-" + invocationId + "-limit-" + turn);
    try {
      runtime.enqueueCommands(
          new ThreadCommandBatch(
              snapshot.thread().id(),
              snapshot.thread().headEntryId(),
              snapshot.thread().nextCommandSequence(),
              List.of(reminder)));
      return true;
    } catch (HarnessRuntimeConflictException conflict) {
      return false;
    }
  }

  private void publishStatus(
      ToolExecutionListener listener,
      ToolCall call,
      String subagentType,
      ThreadSnapshot snapshot,
      long sourceHeadEntryId,
      List<SubagentRunRegistry.RelayedStatus> descendants) {
    SubagentRunRegistry.RelayedStatus current =
        new SubagentRunRegistry.RelayedStatus(
            snapshot.thread().id(),
            subagentType,
            taskState(snapshot),
            depth(snapshot),
            countTurns(snapshot, sourceHeadEntryId),
            countToolCalls(snapshot, sourceHeadEntryId),
            lastActivity(snapshot),
            pendingApprovals(snapshot));
    runRegistry.publishStatus(current);
    ObjectNode status = objectMapper.createObjectNode();
    status.put("kind", "task.status");
    writeStatus(status, current);
    var descendantNodes = status.putArray("descendants");
    for (SubagentRunRegistry.RelayedStatus descendant : descendants) {
      writeStatus(descendantNodes.addObject(), descendant);
    }
    String statusJson = status.toString();
    listener.onPartial(
        new ToolResult(
            call.id(), List.of(new TextToolContent(statusJson + "\n")), false, statusJson, false));
  }

  private static List<SubagentRunRegistry.RelayedApproval> pendingApprovals(
      ThreadSnapshot snapshot) {
    List<SubagentRunRegistry.RelayedApproval> approvals = new ArrayList<>();
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      ToolApproval approval = tool.approval();
      if (approval == null || !approval.required() || approval.decision() != null) {
        continue;
      }
      approvals.add(
          new SubagentRunRegistry.RelayedApproval(
              tool.id(), tool.request().call().toolName(), approval.reason()));
    }
    return List.copyOf(approvals);
  }

  private static void writeStatus(ObjectNode node, SubagentRunRegistry.RelayedStatus status) {
    node.put("threadId", Long.toString(status.threadId()));
    node.put("subagentType", status.subagentType());
    node.put("state", status.state());
    node.put("depth", status.depth());
    node.put("turns", status.turns());
    node.put("toolCalls", status.toolCalls());
    node.put("lastActivity", status.lastActivity());
    var approvals = node.putArray("approvals");
    for (SubagentRunRegistry.RelayedApproval approval : status.approvals()) {
      ObjectNode item = approvals.addObject();
      item.put("invocationId", Long.toString(approval.invocationId()));
      item.put("toolName", approval.toolName());
      item.put("reason", approval.reason());
    }
  }

  private RunResult terminalResult(ThreadSnapshot snapshot, long sourceHeadEntryId) {
    if (!snapshot.queuedCommands().isEmpty()
        || snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()) {
      return null;
    }
    int sourceIndex = indexOf(snapshot, sourceHeadEntryId);
    if (!(snapshot.entryPath().head().payload() instanceof TurnEndPayload end)
        || end.continueModel()
        || sourceIndex < 0
        || sourceIndex >= snapshot.entryPath().entries().size() - 1) {
      return null;
    }
    String report = lastReport(snapshot, sourceHeadEntryId);
    return switch (end.outcome()) {
      case COMPLETED -> new RunResult(RunState.COMPLETED, report);
      case STOPPED, CANCELLED -> new RunResult(RunState.CANCELLED, report);
      case FAILED -> new RunResult(RunState.ERROR, report);
    };
  }

  private static int countTurns(ThreadSnapshot snapshot, long sourceHeadEntryId) {
    int start = indexOf(snapshot, sourceHeadEntryId);
    int count = 0;
    for (int i = Math.max(0, start + 1); i < snapshot.entryPath().entries().size(); i++) {
      if (snapshot.entryPath().entries().get(i).payload() instanceof TurnStartPayload turn
          && turn.reason() != TurnStartReason.COMPACTION) {
        count++;
      }
    }
    return count;
  }

  private static int countToolCalls(ThreadSnapshot snapshot, long sourceHeadEntryId) {
    int start = indexOf(snapshot, sourceHeadEntryId);
    int count = 0;
    for (int i = Math.max(0, start + 1); i < snapshot.entryPath().entries().size(); i++) {
      if (!(snapshot.entryPath().entries().get(i).payload() instanceof MessagePayload message)
          || message.message().role() != AgentMessageRole.ASSISTANT) {
        continue;
      }
      for (AgentMessageContent content : message.message().contents()) {
        if (content instanceof ToolCallMessageContent) {
          count++;
        }
      }
    }
    return count;
  }

  private static String lastReport(ThreadSnapshot snapshot, long sourceHeadEntryId) {
    int start = indexOf(snapshot, sourceHeadEntryId);
    String report = null;
    for (int i = Math.max(0, start + 1); i < snapshot.entryPath().entries().size(); i++) {
      var payload = snapshot.entryPath().entries().get(i).payload();
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        String text = messageText(message.message());
        if (!text.isBlank()) {
          report = text;
        }
      } else if (payload instanceof AssistantErrorPayload error) {
        report = error.error().message();
      } else if (payload instanceof AssistantAbortedPayload aborted) {
        String text = messageText(aborted.message());
        if (!text.isBlank()) {
          report = text;
        }
      }
    }
    return report == null || report.isBlank() ? "(no textual report produced)" : report;
  }

  private static String messageText(AgentMessage message) {
    List<String> parts = new ArrayList<>();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        parts.add(text.text());
      } else if (content instanceof JsonMessageContent json) {
        parts.add(json.json());
      }
    }
    return String.join("", parts).trim();
  }

  private static int indexOf(ThreadSnapshot snapshot, long entryId) {
    List<Entry> entries = snapshot.entryPath().entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id() == entryId) {
        return i;
      }
    }
    return -1;
  }

  private static int depth(ThreadSnapshot snapshot) {
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    return root.subagentContext() == null ? 1 : root.subagentContext().depth();
  }

  private static String taskState(ThreadSnapshot snapshot) {
    boolean waitingApproval =
        snapshot.toolSiblings().stream()
            .map(ToolInvocation::approval)
            .filter(Objects::nonNull)
            .anyMatch(approval -> approval.required() && approval.decision() == null);
    if (waitingApproval) {
      return "waiting_approval";
    }
    if (!snapshot.toolSiblings().isEmpty()) {
      return "running_tool";
    }
    if (snapshot.model() != null) {
      return "running_model";
    }
    return "queued";
  }

  private static String lastActivity(ThreadSnapshot snapshot) {
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      return tool.status().name().toLowerCase() + " " + tool.request().call().toolName();
    }
    if (snapshot.model() != null) {
      return snapshot.model().status().name().toLowerCase();
    }
    return snapshot.queuedCommands().isEmpty() ? "settling" : "queued";
  }

  private static String fingerprint(ThreadSnapshot snapshot) {
    StringBuilder value =
        new StringBuilder()
            .append(snapshot.thread().revision())
            .append('|')
            .append(snapshot.thread().headEntryId())
            .append('|');
    if (snapshot.model() != null) {
      value
          .append(snapshot.model().id())
          .append(':')
          .append(snapshot.model().attempt())
          .append(':')
          .append(snapshot.model().status())
          .append(':')
          .append(snapshot.model().updatedAt());
    }
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      value
          .append('|')
          .append(tool.id())
          .append(':')
          .append(tool.attempt())
          .append(':')
          .append(tool.status())
          .append(':')
          .append(tool.updatedAt())
          .append(':')
          .append(tool.approval());
    }
    return value.toString();
  }

  private void cancelChild(HarnessRuntime runtime, long threadId, long taskInvocationId) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
        runtime.stop(
            new StopCommand(
                threadId, "task-" + taskInvocationId + "-cancel", snapshot.thread().revision()));
        return;
      } catch (HarnessRuntimeConflictException stale) {
        // revision 前进时重读后重试。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return;
      }
    }
  }

  private void complete(
      ToolExecutionListener listener,
      ToolCall call,
      String subagentType,
      long threadId,
      RunState state,
      String report) {
    String canonicalReport =
        report == null || report.isBlank() ? "(no textual report produced)" : report;
    String boundedReport = truncate(canonicalReport);
    String text =
        state == RunState.COMPLETED
            ? "<task id=\""
                + (threadId > 0 ? threadId : "")
                + "\" state=\"completed\">\n<task_result>\n"
                + boundedReport
                + "\n</task_result>\n</task>"
            : "<task id=\""
                + (threadId > 0 ? threadId : "")
                + "\" state=\""
                + state.wireName
                + "\">\n<task_error>"
                + boundedReport
                + "</task_error>\n</task>";
    ObjectNode details = objectMapper.createObjectNode();
    details.put("kind", "task.result");
    details.put("threadId", threadId > 0 ? Long.toString(threadId) : "");
    details.put("subagentType", subagentType);
    details.put("state", state.wireName);
    listener.onComplete(
        new ToolResult(
            call.id(),
            List.of(new TextToolContent(text)),
            state != RunState.COMPLETED,
            details.toString(),
            false));
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimeProvider.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("HarnessRuntime is not available");
    }
    return runtime;
  }

  private Arguments parseArguments(ToolCall call) {
    JsonNode value;
    try {
      value = objectMapper.readTree(call.argumentsJson());
    } catch (JsonProcessingException error) {
      throw reject("task arguments are not valid JSON");
    }
    if (!(value instanceof ObjectNode node)) {
      throw reject("task arguments must be a JSON object");
    }
    String subagentType = requiredText(node, "subagent_type");
    String prompt = requiredText(node, "prompt");
    int maxTurns = config.maxTurns();
    JsonNode maxTurnsNode = node.get("maxTurns");
    if (maxTurnsNode != null && !maxTurnsNode.isNull()) {
      if (!maxTurnsNode.canConvertToInt() || !maxTurnsNode.isIntegralNumber()) {
        throw reject("maxTurns must be a positive integer");
      }
      maxTurns = maxTurnsNode.intValue();
      if (maxTurns < 1) {
        throw reject("maxTurns must be a positive integer");
      }
    }
    Long sessionId = null;
    JsonNode sessionNode = node.get("session_id");
    if (sessionNode != null && !sessionNode.isNull()) {
      if (!sessionNode.isTextual()) {
        throw reject("session_id must be a positive decimal string");
      }
      String raw = sessionNode.textValue();
      try {
        sessionId = Long.parseLong(raw);
      } catch (NumberFormatException error) {
        throw reject("session_id must be a positive decimal string");
      }
      if (sessionId <= 0 || !Long.toString(sessionId).equals(raw)) {
        throw reject("session_id must be a positive decimal string");
      }
    }
    return new Arguments(subagentType, prompt, maxTurns, sessionId);
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw reject(field + " is required");
    }
    String text = value.textValue().strip();
    if (field.equals("subagent_type") && !text.equals(value.textValue())) {
      throw reject("subagent_type must not contain surrounding whitespace");
    }
    return text;
  }

  private static String safeSubagentType(ToolCall call) {
    return call.toolName();
  }

  private static String availableNames(List<SubagentBinding> bindings) {
    return bindings.stream()
        .map(SubagentBinding::name)
        .reduce((left, right) -> left + " / " + right)
        .orElse("none");
  }

  private static String cancelledMessage(long threadId) {
    return "Cancelled by user. Session preserved as `" + threadId + "`; resume it with session_id.";
  }

  private static String truncate(String text) {
    if (text.length() <= REPORT_FALLBACK_MAX_CHARS) {
      return text;
    }
    return text.substring(0, REPORT_FALLBACK_MAX_CHARS) + "\n... (truncated)";
  }

  private static TaskRejectedException reject(String message) {
    return new TaskRejectedException(message);
  }

  private record Arguments(String subagentType, String prompt, int maxTurns, Long sessionId) {}

  private record ParentContext(
      ThreadSnapshot snapshot,
      int depth,
      long rootThreadId,
      List<SubagentBinding> allowedSubagents) {}

  private record RunResult(RunState state, String report) {}

  private enum RunState {
    COMPLETED("completed"),
    ERROR("error"),
    CANCELLED("cancelled");

    private final String wireName;

    RunState(String wireName) {
      this.wireName = wireName;
    }
  }

  private static final class TaskRejectedException extends RuntimeException {
    private TaskRejectedException(String message) {
      super(message);
    }
  }
}
