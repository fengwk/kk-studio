package fun.fengwk.kkstudio.harness.runtime.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.ChangeGate;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 以普通 durable Harness Thread 运行隔离子 Agent 的内部 task 平台工具。 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";
  public static final String VERSION = "1";
  public static final String RENDERER_KEY = "task";

  private static final int MAX_TURNS_REMINDER_INTERVAL = 5;
  private static final int REPORT_FALLBACK_MAX_CHARS = 8_000;
  private static final long STATUS_HEARTBEAT_NANOS = Duration.ofSeconds(1).toNanos();

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final SubagentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfigProvider configProvider;
  private final SubagentRunRegistry runRegistry;
  private final HarnessThreadChangeSource changeSource;
  private final ExecutorService executor;
  private final ObjectMapper objectMapper;

  public TaskTool(
      Supplier<HarnessRuntime> runtimeProvider,
      SubagentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider configProvider,
      SubagentRunRegistry runRegistry,
      HarnessThreadChangeSource changeSource,
      ExecutorService executor,
      ObjectMapper objectMapper) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry");
    this.changeSource = Objects.requireNonNull(changeSource, "changeSource");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * 每次调用按当前 {@link SubagentConfig#maxTurns()} 现拼 descriptor：描述与 schema 的默认 maxTurns 随 aiRuntime 配置
   * live 生效，绝不冻结装配期快照。
   */
  @Override
  public ToolDescriptor descriptor() {
    int defaultMaxTurns = configProvider.subagentConfig().maxTurns();
    return new ToolDescriptor(
        NAME,
        VERSION,
        ToolType.PLATFORM,
        SubagentPrompts.taskToolDescription(),
        RENDERER_KEY,
        SubagentPrompts.taskInputSchema(defaultMaxTurns),
        ToolSideEffect.NON_IDEMPOTENT,
        Duration.ZERO);
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
    private final AtomicBoolean cancelChildStarted = new AtomicBoolean();
    private final AtomicReference<UUID> childThreadId = new AtomicReference<>();
    private final ChangeGate gate = new ChangeGate();

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
              null,
              RunState.CANCELLED,
              "Cancelled before the subagent session started.");
          return;
        }
        UUID resumeThreadId = arguments.sessionId();
        try (SubagentRunRegistry.Reservation reservation = reserve(parent, resumeThreadId)) {
          UUID sourceHeadEntryId;
          ThreadSnapshot child;
          if (resumeThreadId == null) {
            // 全新子 Session：NEW_SESSION 一次原子物化 Session/Thread/初始 prompt Command/Work。
            child =
                createChild(
                    runtime,
                    parent,
                    selected.name(),
                    arguments.prompt(),
                    request.context().invocationId());
            sourceHeadEntryId = child.thread().headEntryId();
          } else {
            child = resumeChild(runtime, parent, selected.name(), resumeThreadId);
            // 恢复子 Session：目标 Agent 可切换，完整 settings diff + prompt 在一次 THREAD 接受中提交。
            BranchSettings target =
                settingsMaterializer.materialize(
                    selected.name(), child.entryPath().baseSettings().environment(), depth(child));
            List<NewThreadCommand> commands =
                taskCommands(child.entryPath().baseSettings(), target, arguments.prompt());
            sourceHeadEntryId = child.thread().headEntryId();
            enqueue(runtime, child, commands);
          }
          UUID threadId = child.thread().id();
          childThreadId.set(threadId);
          reservation.attach(threadId);
          if (cancelled.get()) {
            cancelChildOnce(runtime, threadId, request.context().invocationId());
            complete(
                call, selected.name(), threadId, RunState.CANCELLED, cancelledMessage(threadId));
            return;
          }
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
      // 主动唤醒观察循环，使取消在同一 wake 通道上立即生效（不依赖任何 durable 事件）。
      gate.cancel();
      UUID threadId = childThreadId.get();
      if (threadId != null) {
        try {
          // 与观察循环/attach 后检查共享 single-owner 执行权：整个 TaskExecution 至多一条 stop 重试序列。
          cancelChildOnce(requireRuntime(), threadId, request.context().invocationId());
        } catch (RuntimeException ignored) {
          // 父 Tool stop 已是 durable 事实；子 Thread 的 best-effort cascade 失败不能反转它。
        }
      }
    }

    /** 整个 TaskExecution 至多执行一条 3-attempt stop 重试序列：cancel()/attach 后检查/观察循环共享执行权。 */
    private void cancelChildOnce(HarnessRuntime runtime, UUID threadId, UUID taskInvocationId) {
      if (!cancelChildStarted.compareAndSet(false, true)) {
        return;
      }
      try {
        cancelChild(runtime, threadId, taskInvocationId);
      } catch (RuntimeException ignored) {
        // best-effort：stop 序列失败不反转取消/超时终态（与 cancel() 调用路径一致）。
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    private void complete(
        ToolCall call, String subagentType, UUID threadId, RunState state, String report) {
      TaskTool.this.complete(listener, call, subagentType, threadId, state, report);
    }

    private void publishStatus(
        ToolCall call,
        String subagentType,
        ThreadSnapshot snapshot,
        UUID sourceHeadEntryId,
        List<SubagentRunRegistry.RelayedStatus> descendants) {
      TaskTool.this.publishStatus(
          listener, call, subagentType, snapshot, sourceHeadEntryId, descendants);
    }

    private RunResult awaitResult(
        HarnessRuntime runtime,
        ToolCall call,
        String subagentType,
        UUID threadId,
        UUID sourceHeadEntryId,
        int maxTurns) {
      int nextReminderTurn = maxTurns;
      String previousFingerprint = "";
      List<SubagentRunRegistry.RelayedStatus> previousDescendants = List.of();
      long lastActivityNanos = System.nanoTime();
      long lastStatusNanos = lastActivityNanos;
      long idleDeadlineNanos = Long.MAX_VALUE;
      ThreadSnapshot snapshot = null;
      boolean first = true;
      ChangeGate.State since = gate.snapshot();
      try (HarnessThreadChangeSource.Subscription revisionSubscription =
              changeSource.subscribe(threadId, gate::revision);
          SubagentRunRegistry.ChangeSubscription descendantSubscription =
              runRegistry.subscribeDescendants(threadId, gate::descendants)) {
        while (true) {
          if (cancelled.get()) {
            cancelChildOnce(runtime, threadId, request.context().invocationId());
            return new RunResult(RunState.CANCELLED, cancelledMessage(threadId));
          }
          long now = System.nanoTime();
          long waitNanos;
          if (!first && now - lastStatusNanos < STATUS_HEARTBEAT_NANOS) {
            waitNanos = STATUS_HEARTBEAT_NANOS - (now - lastStatusNanos);
          } else {
            // 首次迭代立即执行权威首读；heartbeat 到点立即发布（不等待信号）。
            waitNanos = 0L;
          }
          if (idleDeadlineNanos != Long.MAX_VALUE) {
            // idle 到期由限时等待本身唤醒：timeout wake 不读 durable snapshot，按缓存 active-tools 语义触发。
            waitNanos = Math.min(waitNanos, Math.max(0L, idleDeadlineNanos - now));
          }
          ChangeGate.State before = since;
          try {
            gate.awaitChange(since, waitNanos);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task observation thread was interrupted", interrupted);
          }
          since = gate.snapshot();
          boolean revisionWake = first || since.revision() != before.revision();
          first = false;
          if (revisionWake) {
            // 权威 durable snapshot 只在首读或 revision/resync 唤醒后读取；heartbeat/descendant 唤醒复用上一份缓存。
            snapshot = runtime.getThreadSnapshot(threadId);
            String fingerprint = fingerprint(snapshot);
            if (!fingerprint.equals(previousFingerprint)) {
              previousFingerprint = fingerprint;
              lastActivityNanos = System.nanoTime();
            }
            idleDeadlineNanos = idleDeadline(snapshot, lastActivityNanos);
          }
          List<SubagentRunRegistry.RelayedStatus> descendants =
              runRegistry.descendantStatuses(threadId);
          boolean relayChanged = !descendants.equals(previousDescendants);
          if (relayChanged) {
            previousDescendants = descendants;
          }
          if (revisionWake
              || relayChanged
              || System.nanoTime() - lastStatusNanos >= STATUS_HEARTBEAT_NANOS) {
            publishStatus(call, subagentType, snapshot, sourceHeadEntryId, descendants);
            lastStatusNanos = System.nanoTime();
          }
          if (revisionWake) {
            // reminder/terminal 只在初始或 revision wake 后按权威 snapshot 判断。
            RunResult terminal = terminalResult(snapshot, sourceHeadEntryId);
            if (terminal != null) {
              return terminal;
            }
            int turns = countTurns(snapshot, sourceHeadEntryId);
            if (turns >= nextReminderTurn && enqueueReminder(runtime, snapshot, nextReminderTurn)) {
              nextReminderTurn = Math.addExact(nextReminderTurn, MAX_TURNS_REMINDER_INTERVAL);
            }
          }
          // idle 检查对任何 wake 类别都生效（含限时等待自然到期）；deadline 依据缓存 snapshot 的 active-tools 语义
          // 计算，到期时不重读 durable snapshot。
          if (snapshot != null
              && snapshot.toolSiblings().isEmpty()
              && idleDeadlineNanos != Long.MAX_VALUE
              && System.nanoTime() >= idleDeadlineNanos) {
            cancelChildOnce(runtime, threadId, request.context().invocationId());
            return new RunResult(
                RunState.ERROR,
                "Subagent idle timeout after "
                    + configProvider.subagentConfig().idleTimeout()
                    + ". Session preserved as `"
                    + threadId
                    + "`.");
          }
        }
      }
    }

    private long idleDeadline(ThreadSnapshot snapshot, long lastActivityNanos) {
      SubagentConfig config = configProvider.subagentConfig();
      if (config.idleTimeout().isZero() || !snapshot.toolSiblings().isEmpty()) {
        return Long.MAX_VALUE;
      }
      return saturatingAdd(lastActivityNanos, config.idleTimeout().toNanos());
    }

    private static long saturatingAdd(long value, long delta) {
      long sum = value + delta;
      return ((value ^ sum) & (delta ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
  }

  private ParentContext parentContext(HarnessRuntime runtime, ToolExecutionRequest request) {
    UUID parentThreadId = request.context().threadId();
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(parentThreadId);
    if (snapshot.model() == null
        || snapshot.toolSiblings().stream()
            .noneMatch(tool -> tool.id().equals(request.context().invocationId()))) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    int depth = root.subagentContext() == null ? 1 : root.subagentContext().depth();
    if (depth >= configProvider.subagentConfig().maxDepth()) {
      throw reject(
          "subagent max depth reached: "
              + depth
              + "/"
              + configProvider.subagentConfig().maxDepth());
    }
    List<SubagentBinding> allowed = snapshot.model().request().subagentBindings();
    if (allowed.isEmpty()) {
      throw reject("the frozen parent invocation does not allow subagent delegation");
    }
    UUID rootThreadId =
        root.subagentContext() == null ? parentThreadId : root.subagentContext().rootThreadId();
    return new ParentContext(snapshot, depth, rootThreadId, allowed);
  }

  private SubagentRunRegistry.Reservation reserve(ParentContext parent, UUID resumeThreadId) {
    try {
      return runRegistry.reserve(
          parent.snapshot().thread().id(),
          parent.rootThreadId(),
          resumeThreadId,
          configProvider.subagentConfig());
    } catch (IllegalArgumentException error) {
      throw reject(error.getMessage());
    }
  }

  private ThreadSnapshot createChild(
      HarnessRuntime runtime,
      ParentContext parent,
      String subagentType,
      String prompt,
      UUID taskInvocationId) {
    int childDepth = parent.depth() + 1;
    BranchSettings settings =
        settingsMaterializer.materialize(
            subagentType, parent.snapshot().entryPath().baseSettings().environment(), childDepth);
    // NEW_SESSION 一次原子物化 Session/ROOT/Thread/初始 prompt Command 与 THREAD Work。
    try {
      AcceptedCommands accepted =
          runtime.acceptCommands(
              new AcceptCommandsCommand(
                  new AcceptCommandsTarget.NewSession(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      settings,
                      new SubagentContext(
                          parent.snapshot().thread().id(),
                          parent.rootThreadId(),
                          taskInvocationId,
                          childDepth),
                      parent.snapshot().thread().yoloEnabled()),
                  List.of(command(new UserMessageCommandPayload(AgentMessage.user(prompt))))),
              AcceptancePreflight.IDENTITY);
      return runtime.getThreadSnapshot(accepted.thread().id());
    } catch (HarnessRuntimeConflictException conflict) {
      throw reject("subagent session changed before the task prompt could be queued");
    }
  }

  private ThreadSnapshot resumeChild(
      HarnessRuntime runtime, ParentContext parent, String subagentType, UUID threadId) {
    ThreadSnapshot snapshot;
    try {
      snapshot = runtime.getThreadSnapshot(threadId);
    } catch (HarnessRuntimeNotFoundException notFound) {
      throw reject("subagent session \"" + threadId + "\" was not found");
    }
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    SubagentContext context = root.subagentContext();
    if (context == null
        || !context.parentThreadId().equals(parent.snapshot().thread().id())
        || !context.rootThreadId().equals(parent.rootThreadId())) {
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
        subagentType, snapshot.entryPath().baseSettings().environment(), context.depth());
    return snapshot;
  }

  private static List<NewThreadCommand> taskCommands(
      BranchSettings current, BranchSettings target, String prompt) {
    List<NewThreadCommand> commands = new ArrayList<>();
    if (!current.agentName().equals(target.agentName())) {
      commands.add(command(new SetAgentCommandPayload(target.agentName())));
    }
    if (!current.model().equals(target.model())) {
      commands.add(command(new SetModelCommandPayload(target.model())));
    }
    if (!current.activeTools().equals(target.activeTools())) {
      commands.add(command(new SetActiveToolsCommandPayload(target.activeTools())));
    }
    commands.add(command(new UserMessageCommandPayload(AgentMessage.user(prompt))));
    return List.copyOf(commands);
  }

  private static NewThreadCommand command(ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(payload));
  }

  private static void enqueue(
      HarnessRuntime runtime, ThreadSnapshot snapshot, List<NewThreadCommand> commands) {
    try {
      // 继续已有子 Session：THREAD target 用精确 expected head/next-sequence 游标接受，完整 settings diff + prompt
      // 一次入队。
      runtime.acceptCommands(
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  snapshot.thread().id(),
                  snapshot.thread().headEntryId(),
                  snapshot.thread().nextCommandSequence()),
              commands),
          AcceptancePreflight.IDENTITY);
    } catch (HarnessRuntimeConflictException conflict) {
      throw reject("subagent session changed before the task prompt could be queued");
    }
  }

  private boolean enqueueReminder(HarnessRuntime runtime, ThreadSnapshot snapshot, int turn) {
    if (snapshot.model() == null && snapshot.toolSiblings().isEmpty()) {
      return false;
    }
    CustomMessageCommandPayload reminderPayload =
        new CustomMessageCommandPayload(AgentMessage.system(SubagentPrompts.maxTurnsReminder()));
    NewThreadCommand reminder =
        new NewThreadCommand(
            reminderPayload,
            UUID.randomUUID(),
            ThreadCommandPayloadJsonCodec.requestHash(reminderPayload));
    try {
      // THREAD steering：恰一条 SYSTEM CUSTOM_MESSAGE 入队。
      runtime.acceptCommands(
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  snapshot.thread().id(),
                  snapshot.thread().headEntryId(),
                  snapshot.thread().nextCommandSequence()),
              List.of(reminder)),
          AcceptancePreflight.IDENTITY);
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
      UUID sourceHeadEntryId,
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
            call.id(), List.of(new TextToolContent(statusJson + "\n")), false, statusJson));
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
              tool.id(), tool.call().toolName(), approval.reason()));
    }
    return List.copyOf(approvals);
  }

  private static void writeStatus(ObjectNode node, SubagentRunRegistry.RelayedStatus status) {
    node.put("threadId", status.threadId().toString());
    node.put("subagentType", status.subagentType());
    node.put("state", status.state());
    node.put("depth", status.depth());
    node.put("turns", status.turns());
    node.put("toolCalls", status.toolCalls());
    node.put("lastActivity", status.lastActivity());
    var approvals = node.putArray("approvals");
    for (SubagentRunRegistry.RelayedApproval approval : status.approvals()) {
      ObjectNode item = approvals.addObject();
      item.put("invocationId", approval.invocationId().toString());
      item.put("toolName", approval.toolName());
      item.put("reason", approval.reason());
    }
  }

  private RunResult terminalResult(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
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

  private static int countTurns(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
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

  private static int countToolCalls(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
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

  private static String lastReport(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
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

  private static int indexOf(ThreadSnapshot snapshot, UUID entryId) {
    List<Entry> entries = snapshot.entryPath().entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
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
      return tool.status().name().toLowerCase() + " " + tool.call().toolName();
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

  private void cancelChild(HarnessRuntime runtime, UUID threadId, UUID taskInvocationId) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().revision()));
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
      UUID threadId,
      RunState state,
      String report) {
    String canonicalReport =
        report == null || report.isBlank() ? "(no textual report produced)" : report;
    String boundedReport = truncate(canonicalReport);
    String idText = threadId == null ? "" : threadId.toString();
    String text =
        state == RunState.COMPLETED
            ? "<task id=\""
                + idText
                + "\" state=\"completed\">\n<task_result>\n"
                + boundedReport
                + "\n</task_result>\n</task>"
            : "<task id=\""
                + idText
                + "\" state=\""
                + state.wireName
                + "\">\n<task_error>"
                + boundedReport
                + "</task_error>\n</task>";
    ObjectNode details = objectMapper.createObjectNode();
    details.put("kind", "task.result");
    details.put("threadId", idText);
    details.put("subagentType", subagentType);
    details.put("state", state.wireName);
    listener.onComplete(
        new ToolResult(
            call.id(),
            List.of(new TextToolContent(text)),
            state != RunState.COMPLETED,
            details.toString()));
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimeProvider.get();
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
    int maxTurns = configProvider.subagentConfig().maxTurns();
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
    UUID sessionId = null;
    JsonNode sessionNode = node.get("session_id");
    if (sessionNode != null && !sessionNode.isNull()) {
      if (!sessionNode.isTextual()) {
        throw reject("session_id must be a canonical UUID string");
      }
      String raw = sessionNode.textValue();
      try {
        sessionId = UUID.fromString(raw);
      } catch (IllegalArgumentException error) {
        throw reject("session_id must be a canonical UUID string");
      }
      if (!sessionId.toString().equals(raw)) {
        throw reject("session_id must be a canonical UUID string");
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

  private static String cancelledMessage(UUID threadId) {
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

  private record Arguments(String subagentType, String prompt, int maxTurns, UUID sessionId) {}

  private record ParentContext(
      ThreadSnapshot snapshot,
      int depth,
      UUID rootThreadId,
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
