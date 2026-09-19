package fun.fengwk.kkstudio.platform.harness.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentPrompts;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
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
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.harness.subagent.SubagentRunRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 使用独立持久化 Harness Thread 执行 {@code task} 子任务。
 *
 * <p>入口会校验父调用冻结的子 Agent 白名单和递归深度，并通过 {@link SubagentRunRegistry} 预占会话。恢复已有会话时还会校验父/根归属和静止态，避免同一
 * Thread 被并发追加命令。
 *
 * <p>等待循环由子 Thread 版本事件和后代状态事件唤醒，同时发送状态心跳、执行空闲超时和最大轮次提醒。父任务取消时对子 Thread 发出尽力停止；停止失败不改变父任务已经进入的取消路径。
 */
public class DatabaseSubagentRunner implements SubagentRunner {

  private static final int MAX_TURNS_REMINDER_INTERVAL = 5;
  private static final int REPORT_FALLBACK_MAX_CHARS = 8_000;
  private static final long STATUS_HEARTBEAT_NANOS = Duration.ofSeconds(1).toNanos();

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final AgentBranchSettingsMaterializer settingsMaterializer;
  private final SubagentConfigProvider configProvider;
  private final SubagentRunRegistry runRegistry;
  private final HarnessThreadChangeSource changeSource;
  private final ExecutorService executor;
  private final ObjectMapper objectMapper;

  public DatabaseSubagentRunner(
      Supplier<HarnessRuntime> runtimeProvider,
      AgentBranchSettingsMaterializer settingsMaterializer,
      SubagentConfigProvider configProvider,
      SubagentRunRegistry runRegistry,
      HarnessThreadChangeSource changeSource,
      @Qualifier("subagentTaskExecutor") ExecutorService executor,
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

  @Override
  public ToolExecutionHandle run(SubagentTaskRequest taskRequest, ToolExecutionListener listener) {
    Objects.requireNonNull(taskRequest, "taskRequest");
    Objects.requireNonNull(listener, "listener");
    TaskExecution execution = new TaskExecution(taskRequest, listener);
    try {
      executor.execute(execution::run);
    } catch (RejectedExecutionException rejected) {
      execution.completeRejected();
    }
    return execution;
  }

  private final class TaskExecution implements ToolExecutionHandle {
    private final SubagentTaskRequest taskRequest;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean cancelChildStarted = new AtomicBoolean();
    private final AtomicReference<UUID> childThreadId = new AtomicReference<>();
    private final ChangeGate gate = new ChangeGate();
    private String resolvedCallId;

    private TaskExecution(SubagentTaskRequest taskRequest, ToolExecutionListener listener) {
      this.taskRequest = taskRequest;
      this.listener = listener;
    }

    /** 校验父上下文，预占并启动或恢复子会话，然后观察到终态。 */
    private void run() {
      try {
        HarnessRuntime runtime = requireRuntime();
        ParentContext parent = parentContext(runtime, taskRequest);
        resolvedCallId = parent.callId();
        SubagentBinding selected =
            parent.allowedSubagents().stream()
                .filter(binding -> binding.name().equals(taskRequest.subagentType()))
                .findFirst()
                .orElseThrow(
                    () ->
                        reject(
                            "subagent_type \""
                                + taskRequest.subagentType()
                                + "\" is not allowed; available: "
                                + availableNames(parent.allowedSubagents())));
        if (cancelled.get()) {
          complete(
              parent.callId(),
              taskRequest.subagentType(),
              null,
              RunState.CANCELLED,
              "Cancelled before the subagent session started.");
          return;
        }
        UUID resumeThreadId = taskRequest.sessionId();
        try (SubagentRunRegistry.Reservation reservation = reserve(parent, resumeThreadId)) {
          UUID sourceHeadEntryId;
          ThreadSnapshot child;
          // 父环境是调用方 Model invocation 冻结的 branch 环境：子 Agent 的开关决定是否继承。
          String parentEnvironmentName =
              parent.snapshot().entryPath().baseSettings().environmentName();
          if (resumeThreadId == null) {
            child =
                createChild(
                    runtime,
                    parent,
                    selected.name(),
                    parentEnvironmentName,
                    taskRequest.prompt(),
                    taskRequest.invocationId());
            sourceHeadEntryId = child.thread().headEntryId();
          } else {
            child = resumeChild(runtime, parent, resumeThreadId);
            // 恢复既有子会话按被调用 Agent 的继承开关重新收敛目标 settings。
            BranchSettings target =
                settingsMaterializer.materializeSubagent(selected.name(), parentEnvironmentName);
            List<NewThreadCommand> commands =
                taskCommands(child.entryPath().baseSettings(), target, taskRequest.prompt());
            sourceHeadEntryId = child.thread().headEntryId();
            enqueue(runtime, child, commands);
          }
          UUID threadId = child.thread().id();
          childThreadId.set(threadId);
          reservation.attach(threadId);
          if (cancelled.get()) {
            cancelChildOnce(runtime, threadId, taskRequest.invocationId());
            complete(
                parent.callId(),
                selected.name(),
                threadId,
                RunState.CANCELLED,
                cancelledMessage(threadId));
            return;
          }
          int maxTurns =
              taskRequest.maxTurns() != null
                  ? taskRequest.maxTurns()
                  : configProvider.subagentConfig().maxTurns();
          RunResult result =
              awaitResult(
                  runtime, parent.callId(), selected.name(), threadId, sourceHeadEntryId, maxTurns);
          complete(parent.callId(), selected.name(), threadId, result.state(), result.report());
        }
      } catch (TaskRejectedException rejected) {
        String callId = resolvedCallId != null ? resolvedCallId : lookupCallId();
        complete(
            callId,
            taskRequest.subagentType(),
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
      gate.cancel();
      UUID threadId = childThreadId.get();
      if (threadId != null) {
        try {
          cancelChildOnce(requireRuntime(), threadId, taskRequest.invocationId());
        } catch (RuntimeException ignored) {
          // 子会话停止是尽力而为；失败不改变父工具的取消路径。
        }
      }
    }

    /** 每个父任务至多启动一次对子会话的尽力停止。 */
    private void cancelChildOnce(HarnessRuntime runtime, UUID threadId, UUID taskInvocationId) {
      if (!cancelChildStarted.compareAndSet(false, true)) {
        return;
      }
      try {
        cancelChild(runtime, threadId, taskInvocationId);
      } catch (RuntimeException ignored) {
        // 停止失败由父任务自己的终态收敛吸收。
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    private void complete(
        String callId, String subagentType, UUID threadId, RunState state, String report) {
      DatabaseSubagentRunner.this.complete(listener, callId, subagentType, threadId, state, report);
    }

    private void completeRejected() {
      try {
        String callId = lookupCallId();
        complete(
            callId,
            taskRequest.subagentType(),
            null,
            RunState.ERROR,
            "Subagent execution capacity is exhausted; retry later.");
      } catch (RuntimeException ignored) {
        // 执行器已拒绝任务，此处不能再让监听器异常逃出调用线程。
      }
    }

    private String lookupCallId() {
      try {
        HarnessRuntime runtime = runtimeProvider.get();
        if (runtime != null) {
          ThreadSnapshot snapshot = runtime.getThreadSnapshot(taskRequest.threadId());
          for (ToolInvocation tool : snapshot.toolSiblings()) {
            if (tool.id().equals(taskRequest.invocationId())) {
              return tool.call().id();
            }
          }
        }
      } catch (RuntimeException ignored) {
        // 拒绝路径仍需稳定的 call ID。
      }
      return taskRequest.invocationId().toString();
    }

    private void publishStatus(
        String callId,
        String subagentType,
        ThreadSnapshot snapshot,
        UUID sourceHeadEntryId,
        List<SubagentRunRegistry.RelayedStatus> descendants) {
      DatabaseSubagentRunner.this.publishStatus(
          listener, callId, subagentType, snapshot, sourceHeadEntryId, descendants);
    }

    /**
     * 等待子会话或后代状态变化，并按心跳发布状态。
     *
     * <p>无活跃工具时执行空闲超时；达到 {@code maxTurns} 后周期性注入收敛提醒。
     */
    private RunResult awaitResult(
        HarnessRuntime runtime,
        String callId,
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
      try (HarnessThreadChangeSource.Subscription versionSubscription =
              changeSource.subscribe(threadId, gate::version);
          SubagentRunRegistry.ChangeSubscription descendantSubscription =
              runRegistry.subscribeDescendants(threadId, gate::descendants)) {
        while (true) {
          if (cancelled.get()) {
            cancelChildOnce(runtime, threadId, taskRequest.invocationId());
            return new RunResult(RunState.CANCELLED, cancelledMessage(threadId));
          }
          long now = System.nanoTime();
          long waitNanos;
          if (!first && now - lastStatusNanos < STATUS_HEARTBEAT_NANOS) {
            waitNanos = STATUS_HEARTBEAT_NANOS - (now - lastStatusNanos);
          } else {
            waitNanos = 0L;
          }
          if (idleDeadlineNanos != Long.MAX_VALUE) {
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
          boolean versionWake = first || since.version() != before.version();
          first = false;
          if (versionWake) {
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
          if (versionWake
              || relayChanged
              || System.nanoTime() - lastStatusNanos >= STATUS_HEARTBEAT_NANOS) {
            publishStatus(callId, subagentType, snapshot, sourceHeadEntryId, descendants);
            lastStatusNanos = System.nanoTime();
          }
          if (versionWake) {
            RunResult terminal = terminalResult(snapshot, sourceHeadEntryId);
            if (terminal != null) {
              return terminal;
            }
            int turns = countTurns(snapshot, sourceHeadEntryId);
            if (turns >= nextReminderTurn && enqueueReminder(runtime, snapshot, nextReminderTurn)) {
              nextReminderTurn = Math.addExact(nextReminderTurn, MAX_TURNS_REMINDER_INTERVAL);
            }
          }
          if (snapshot != null
              && snapshot.toolSiblings().isEmpty()
              && idleDeadlineNanos != Long.MAX_VALUE
              && System.nanoTime() >= idleDeadlineNanos) {
            cancelChildOnce(runtime, threadId, taskRequest.invocationId());
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

  private ParentContext parentContext(HarnessRuntime runtime, SubagentTaskRequest taskRequest) {
    UUID parentThreadId = taskRequest.threadId();
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(parentThreadId);
    if (snapshot.model() == null) {
      throw reject("task invocation is no longer attached to its parent Thread context");
    }
    ToolInvocation parentTool =
        snapshot.toolSiblings().stream()
            .filter(tool -> tool.id().equals(taskRequest.invocationId()))
            .findFirst()
            .orElseThrow(
                () -> reject("task invocation is no longer attached to its parent Thread context"));
    RootPayload root = (RootPayload) snapshot.entryPath().root().payload();
    int depth = root.subagentContext() == null ? 1 : root.subagentContext().depth();
    if (depth >= configProvider.subagentConfig().maxDepth()) {
      throw reject(
          "subagent max depth reached: "
              + depth
              + "/"
              + configProvider.subagentConfig().maxDepth());
    }
    List<SubagentBinding> allowed = snapshot.model().requestSpec().subagentBindings();
    if (allowed.isEmpty()) {
      throw reject("the frozen parent invocation does not allow subagent delegation");
    }
    UUID rootThreadId =
        root.subagentContext() == null ? parentThreadId : root.subagentContext().rootThreadId();
    return new ParentContext(snapshot, depth, rootThreadId, allowed, parentTool.call().id());
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
      String parentEnvironmentName,
      String prompt,
      UUID taskInvocationId) {
    int childDepth = parent.depth() + 1;
    // 新建子会话不继承父目录：目录由每次工具调用显式提供。父环境只按子 Agent 的开关继承。
    BranchSettings settings =
        settingsMaterializer.materializeSubagent(subagentType, parentEnvironmentName);
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

  /** 恢复属于当前父链且没有模型、工具或排队命令的静止子会话。 */
  private ThreadSnapshot resumeChild(HarnessRuntime runtime, ParentContext parent, UUID threadId) {
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
    return snapshot;
  }

  /**
   * 恢复子会话的配置收敛命令前缀：{@code SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT -> USER}。
   *
   * <p>环境只有在与当前目标不同（含清除为 null）时才发送 {@code SET_ENVIRONMENT}，null 表示明确解除该分支的环境选择。
   */
  private static List<NewThreadCommand> taskCommands(
      BranchSettings current, BranchSettings target, String prompt) {
    List<NewThreadCommand> commands = new ArrayList<>();
    if (!current.agentName().equals(target.agentName())) {
      commands.add(command(new SetAgentCommandPayload(target.agentName())));
    }
    if (!current.model().equals(target.model())) {
      commands.add(command(new SetModelCommandPayload(target.model())));
    }
    if (!Objects.equals(current.environmentName(), target.environmentName())) {
      commands.add(command(new SetEnvironmentCommandPayload(target.environmentName())));
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
    // 内部软预算提醒以 USER system-reminder 持久化：系统指令只由请求的 systemInstruction 承载。
    CustomMessageCommandPayload reminderPayload =
        new CustomMessageCommandPayload(SystemReminder.message(SubagentPrompts.maxTurnsReminder()));
    NewThreadCommand reminder =
        new NewThreadCommand(
            reminderPayload,
            UUID.randomUUID(),
            ThreadCommandPayloadJsonCodec.requestHash(reminderPayload));
    try {
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

  /** 向注册表发布当前子 Agent 执行快照并向调用监听器推送状态片段。 */
  private void publishStatus(
      ToolExecutionListener listener,
      String callId,
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
            callId, List.of(new TextResultContent(statusJson + "\n")), false, statusJson));
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
            .append(snapshot.thread().version())
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

  /** 最多以最新 Thread version 重试三次停止子 Agent，目标已不存在时视为完成。 */
  private void cancelChild(HarnessRuntime runtime, UUID threadId, UUID taskInvocationId) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (HarnessRuntimeConflictException stale) {
        // 使用新版本重读后重试停止。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return;
      }
    }
  }

  /** 根据终态运行结果格式化任务报告并通知调用监听器完成。 */
  private void complete(
      ToolExecutionListener listener,
      String callId,
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
            callId,
            List.of(new TextResultContent(text)),
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

  private record ParentContext(
      ThreadSnapshot snapshot,
      int depth,
      UUID rootThreadId,
      List<SubagentBinding> allowedSubagents,
      String callId) {}

  private record RunResult(RunState state, String report) {}

  private enum RunState {
    /** 子 Agent 任务顺利完成。 */
    COMPLETED("completed"),

    /** 子 Agent 任务执行失败或无法产出正常报告。 */
    ERROR("error"),

    /** 子 Agent 任务已被取消。 */
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
