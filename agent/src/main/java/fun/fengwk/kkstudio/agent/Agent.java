package fun.fengwk.kkstudio.agent;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 是主链异步状态机：CAS 调度、信号队列与 drain、对外 API。
 *
 * <p>会话视图与事件持久化委托给 {@link AgentSessionWriter}；assistant 调用生命周期委托给
 * {@link AgentAssistantRunner}；tool batch 生命周期委托给 {@link AgentToolOrchestrator}；
 * 运行时配置解析通过 {@link AgentRuntimeConfigResolver} 由本类持有并在每次主循环转
 * assistant 之前调用。
 *
 * @author fengwk
 */
@Slf4j
public class Agent {

  private final AgentSessionWriter writer;
  private final AgentAssistantRunner assistant;
  private final AgentToolOrchestrator tools;
  private final AgentRuntimeConfigResolver runtimeConfigResolver;
  private final UserRequestQueue userRequestQueue;
  private final AgentScheduler agentScheduler;
  private final ModelRetryConfig modelRetryConfig;

  private final AtomicReference<AgentStatus> statusRef = new AtomicReference<>(AgentStatus.idle);
  private final AtomicBoolean drainingSignals = new AtomicBoolean();
  private final ConcurrentLinkedQueue<AgentSignal> signalQueue = new ConcurrentLinkedQueue<>();

  private String agentName;
  private String provider;
  private String model;
  private String variant;
  private AgentRunContext currentRun;

  public Agent(
      AgentSessionWriter writer,
      AgentAssistantRunner assistant,
      AgentToolOrchestrator tools,
      AgentRuntimeConfigResolver runtimeConfigResolver,
      UserRequestQueue userRequestQueue,
      AgentScheduler agentScheduler,
      ModelRetryConfig modelRetryConfig,
      String agentName,
      String provider,
      String model,
      String variant) {
    this.writer = requireNonNull(writer, "writer");
    this.assistant = requireNonNull(assistant, "assistant");
    this.tools = requireNonNull(tools, "tools");
    this.runtimeConfigResolver = requireNonNull(runtimeConfigResolver, "runtimeConfigResolver");
    this.userRequestQueue = requireNonNull(userRequestQueue, "userRequestQueue");
    this.agentScheduler = requireNonNull(agentScheduler, "agentScheduler");
    this.modelRetryConfig = requireNonNull(modelRetryConfig, "modelRetryConfig");
    this.agentName = requireNonBlank(agentName, "agentName");
    this.provider = provider;
    this.model = model;
    this.variant = variant;
  }

  // ========== 公开 API（保持签名不变） ==========

  public Session getSession() {
    return writer.getSession();
  }

  public Branch getBranch() {
    return writer.getBranch();
  }

  public List<SessionEvent> getBranchEvents() {
    return writer.getBranchEvents();
  }

  public List<AgentMessage> getProjectedMessages() {
    return writer.getProjectedMessages();
  }

  public SetAgentInfoPayload getCurrentAgentInfo() {
    return writer.getCurrentAgentInfo();
  }

  public SetModelInfoPayload getCurrentModelInfo() {
    return writer.getCurrentModelInfo();
  }

  public String getAgentName() {
    return agentName;
  }

  public AgentStatus getStatus() {
    return statusRef.get();
  }

  public void setAgentName(String agentName) {
    this.agentName = requireNonBlank(agentName, "agentName");
  }

  public void setModelSelection(String provider, String model, String variant) {
    this.provider = provider;
    this.model = model;
    this.variant = variant;
  }

  public void submit(UserRequest userRequest) {
    if (userRequest == null) {
      throw new IllegalArgumentException("userRequest must not be null");
    }
    userRequestQueue.submit(userRequest);
    triggerMainLoop();
  }

  public void abort(String reason) {
    enqueueSignal(new AbortSignal(reason));
  }

  public void switchBranch(Branch branch, List<SessionEvent> branchEvents) {
    if (branch == null) {
      throw new IllegalArgumentException("branch must not be null");
    }
    if (branchEvents == null) {
      throw new IllegalArgumentException("branchEvents must not be null");
    }
    enqueueSignal(new SwitchBranchSignal(branch, List.copyOf(branchEvents)));
  }

  public SessionEventProjection projection() {
    return writer.projection();
  }

  // ========== 信号队列与 drain ==========

  /** 给协作者调用：把 provider/tool callback 包成的信号入队。 */
  void enqueueSignal(AgentSignal signal) {
    signalQueue.offer(signal);
    drainSignals();
  }

  private void drainSignals() {
    while (true) {
      if (!drainingSignals.compareAndSet(false, true)) {
        return;
      }
      try {
        AgentSignal signal;
        while ((signal = signalQueue.poll()) != null) {
          try {
            handleSignal(signal);
          } catch (Throwable error) {
            failCurrentRun(error);
          }
        }
      } finally {
        drainingSignals.set(false);
      }
      if (!signalQueue.isEmpty()) {
        continue;
      }
      return;
    }
  }

  private void handleSignal(AgentSignal signal) {
    if (signal instanceof StartLoopSignal) {
      onStartLoop();
    } else if (signal instanceof RetryAssistantSignal retryAssistantSignal) {
      onRetryAssistant(retryAssistantSignal);
    } else if (signal instanceof AbortSignal abortSignal) {
      onAbort(abortSignal);
    } else if (signal instanceof SwitchBranchSignal switchBranchSignal) {
      onSwitchBranch(switchBranchSignal);
    } else if (signal instanceof AssistantTextDeltaSignal t) {
      assistant.onTextDelta(currentRun, t);
    } else if (signal instanceof AssistantThinkingDeltaSignal t) {
      assistant.onThinkingDelta(currentRun, t);
    } else if (signal instanceof AssistantToolCallDeltaSignal t) {
      assistant.onToolCallDelta(currentRun, t);
    } else if (signal instanceof AssistantToolCallCompleteSignal t) {
      assistant.onToolCallComplete(currentRun, t);
    } else if (signal instanceof AssistantCompleteSignal c) {
      assistant.onComplete(currentRun, c);
    } else if (signal instanceof AssistantErrorSignal e) {
      onAssistantError(e);
    } else if (signal instanceof ToolPartialSignal p) {
      tools.onPartial(currentRun, p);
    } else if (signal instanceof ToolCompleteSignal c) {
      tools.onComplete(currentRun, c);
    } else if (signal instanceof ToolErrorSignal e) {
      tools.onError(currentRun, e);
    }
  }

  // ========== 主循环启动/终止 ==========

  private void triggerMainLoop() {
    if (userRequestQueue.isEmpty()) {
      return;
    }
    if (!statusRef.compareAndSet(AgentStatus.idle, AgentStatus.busy)) {
      return;
    }
    enqueueSignal(StartLoopSignal.INSTANCE);
  }

  private void onStartLoop() {
    if (currentRun != null) {
      return;
    }
    List<String> userMessages = harvestUserMessages();
    if (userMessages.isEmpty()) {
      releaseLoop();
      return;
    }
    currentRun = new AgentRunContext();
    startAssistantAttempt(userMessages);
  }

  private void onRetryAssistant(RetryAssistantSignal signal) {
    if (currentRun == null || currentRun != signal.runContext() || currentRun.aborted) {
      return;
    }
    currentRun.scheduledTask = null;
    startAssistantAttempt(List.of());
  }

  private void onAbort(AbortSignal signal) {
    if (currentRun == null) {
      return;
    }
    cancelCurrentRunResources();
    AbortPayload payload = new AbortPayload();
    payload.setReason(signal.reason());
    writer.appendEvent(SessionEventType.abort, payload);
    releaseLoop();
  }

  private void onSwitchBranch(SwitchBranchSignal signal) {
    cancelCurrentRunResources();
    currentRun = null;
    statusRef.set(AgentStatus.idle);
    writer.switchBranch(signal.branch(), signal.branchEvents());
    if (!userRequestQueue.isEmpty()) {
      triggerMainLoop();
    }
  }

  private void releaseLoop() {
    currentRun = null;
    statusRef.set(AgentStatus.idle);
    if (!userRequestQueue.isEmpty()) {
      triggerMainLoop();
    }
  }

  private void failCurrentRun(Throwable error) {
    log.error("[agent] current run failed unexpectedly", error);
    cancelCurrentRunResources();
    releaseLoop();
  }

  private void cancelCurrentRunResources() {
    if (currentRun == null) {
      return;
    }
    currentRun.aborted = true;
    if (currentRun.scheduledTask != null) {
      try {
        currentRun.scheduledTask.cancel();
      } catch (RuntimeException error) {
        log.warn("[agent] scheduled task cancel failed", error);
      }
      currentRun.scheduledTask = null;
    }
    assistant.cancelActive(currentRun);
    currentRun.activeAssistant = null;
    tools.cancelAll(currentRun);
  }

  // ========== assistant 启动（含运行时配置刷新） ==========

  private void startAssistantAttempt(List<String> userMessages) {
    if (currentRun == null || currentRun.aborted) {
      return;
    }
    AgentRuntimeConfigResolver.ResolvedRuntimeConfig runtimeConfig;
    try {
      runtimeConfig = resolveRuntimeConfig();
    } catch (RuntimeException error) {
      // runtime config 解析失败时 assistant 尚未启动，直接释放当前 loop。
      if (!currentRun.aborted) {
        failCurrentRun(error);
      }
      return;
    }

    this.provider = runtimeConfig.getResolvedProvider();
    this.model = runtimeConfig.getResolvedModel();
    this.variant = runtimeConfig.getResolvedVariant();

    assistant.startAttempt(currentRun, runtimeConfig, userMessages);
  }

  /** 解析运行时配置并按需落 set_agent_info / set_model_info event。 */
  private AgentRuntimeConfigResolver.ResolvedRuntimeConfig resolveRuntimeConfig() {
    AgentRuntimeConfigResolver.ResolvedRuntimeConfig runtimeConfig =
        runtimeConfigResolver.resolve(agentName, provider, model, variant);
    if (!Objects.equals(runtimeConfig.getAgentPayload(), writer.getCurrentAgentInfo())) {
      writer.appendEvent(
          SessionEventType.set_agent_info,
          runtimeConfig.getAgentPayload());
    }
    if (!Objects.equals(runtimeConfig.getModelPayload(), writer.getCurrentModelInfo())) {
      writer.appendEvent(
          SessionEventType.set_model_info,
          runtimeConfig.getModelPayload());
    }
    return runtimeConfig;
  }

  /**
   * assistant error 信号处理：先把 error 信号交给 runner 写事件 + 清状态，然后由本类决定
   * 是否 abort / 调度 retry。
   */
  private void onAssistantError(AssistantErrorSignal signal) {
    if (!assistant.onError(currentRun, signal)) {
      return;
    }
    if (currentRun == null || currentRun.aborted) {
      releaseLoop();
      return;
    }
    if (currentRun.retryCount >= modelRetryConfig.getMaxRetries()) {
      releaseLoop();
      return;
    }
    currentRun.retryCount++;
    Duration delay = modelRetryConfig.nextDelay(currentRun.retryCount);
    AgentRunContext runContext = currentRun;
    currentRun.scheduledTask =
        agentScheduler.schedule(delay, () -> enqueueSignal(new RetryAssistantSignal(runContext)));
  }

  // ========== tool batch 收尾协调 ==========

  /** 由 AgentToolOrchestrator 在 all tools closed 时回调。 */
  void continueAssistantFromToolBatch() {
    if (currentRun == null || currentRun.aborted) {
      return;
    }
    currentRun.retryCount = 0;
    startAssistantAttempt(harvestUserMessages());
  }

  /** 由 AgentAssistantRunner 在 onComplete 后回调，启动 tool batch。 */
  void startToolBatch(List<ToolCall> toolCalls) {
    if (currentRun == null || currentRun.aborted) {
      return;
    }
    if (toolCalls == null || toolCalls.isEmpty()) {
      // 既然 assistant 已经完成且没有 tool call，就此结束本轮
      releaseLoop();
      return;
    }
    tools.startBatch(currentRun, toolCalls);
  }

  // ========== helpers ==========

  private List<String> harvestUserMessages() {
    List<UserRequest> requests = userRequestQueue.pollAll();
    if (requests == null || requests.isEmpty()) {
      return List.of();
    }
    List<String> messages = new ArrayList<>();
    for (UserRequest request : requests) {
      if (request != null && request.getMessage() != null && !request.getMessage().isBlank()) {
        messages.add(request.getMessage());
      }
    }
    return messages;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
