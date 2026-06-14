package fun.fengwk.kkstudio.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.runtime.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.runtime.SessionEventProjection;
import fun.fengwk.kkstudio.agent.runtime.ToolCallMapper;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.ErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionContext;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 是一次会话执行上下文的承载对象。
 *
 * @author fengwk
 */
public class Agent {

    private final ToolCallMapper toolCallMapper = new ToolCallMapper();
    private final UserRequestQueue userRequestQueue;
    private final AgentEventHandler agentEventHandler;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final SessionEventMessageProjector sessionEventMessageProjector;
    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentScheduler agentScheduler;
    private final ModelRetryConfig modelRetryConfig;
    private final AtomicReference<AgentStatus> statusRef = new AtomicReference<>(AgentStatus.idle);
    private final AtomicBoolean drainingSignals = new AtomicBoolean();
    private final ConcurrentLinkedQueue<AgentSignal> signalQueue = new ConcurrentLinkedQueue<>();
    private Session session;
    private Branch branch;
    private List<SessionEvent> branchEvents;
    private List<ChatMessage> projectedMessages;
    private SetAgentInfoPayload currentAgentInfo;
    private SetModelInfoPayload currentModelInfo;
    private String agentName;
    private String provider;
    private String model;
    private String variant;
    private AgentRunContext currentRun;

    public Agent(Session session,
                 Branch branch,
                 List<SessionEvent> branchEvents,
                 SetAgentInfoPayload currentAgentInfo,
                 SetModelInfoPayload currentModelInfo,
                 String agentName,
                 String provider,
                 String model,
                 String variant,
                 UserRequestQueue userRequestQueue,
                 AgentEventHandler agentEventHandler,
                 ToolRegistry toolRegistry,
                 SessionManager sessionManager,
                 SessionEventMessageProjector sessionEventMessageProjector,
                 AgentRegistry agentRegistry,
                 ModelRegistry modelRegistry,
                 ProviderRegistry providerRegistry,
                 ProviderManager providerManager,
                 AgentScheduler agentScheduler,
                 ModelRetryConfig modelRetryConfig) {
        this.session = requireNonNull(session, "session");
        this.branch = requireNonNull(branch, "branch");
        this.branchEvents = new ArrayList<>(requireNonNull(branchEvents, "branchEvents"));
        this.currentAgentInfo = currentAgentInfo;
        this.currentModelInfo = currentModelInfo;
        this.agentName = requireNonBlank(agentName, "agentName");
        this.provider = provider;
        this.model = model;
        this.variant = variant;
        this.userRequestQueue = requireNonNull(userRequestQueue, "userRequestQueue");
        this.agentEventHandler = requireNonNull(agentEventHandler, "agentEventHandler");
        this.toolRegistry = requireNonNull(toolRegistry, "toolRegistry");
        this.sessionManager = requireNonNull(sessionManager, "sessionManager");
        this.sessionEventMessageProjector = requireNonNull(sessionEventMessageProjector, "sessionEventMessageProjector");
        this.runtimeConfigResolver = new AgentRuntimeConfigResolver(
            requireNonNull(agentRegistry, "agentRegistry"),
            requireNonNull(modelRegistry, "modelRegistry"),
            requireNonNull(providerRegistry, "providerRegistry"),
            requireNonNull(providerManager, "providerManager"),
            toolRegistry);
        this.agentScheduler = requireNonNull(agentScheduler, "agentScheduler");
        this.modelRetryConfig = requireNonNull(modelRetryConfig, "modelRetryConfig");
        this.projectedMessages = List.of();
        refreshProjection();
    }

    public Session getSession() {
        return session;
    }

    public Branch getBranch() {
        return branch;
    }

    public List<SessionEvent> getBranchEvents() {
        return List.copyOf(branchEvents);
    }

    public List<ChatMessage> getProjectedMessages() {
        return projectedMessages;
    }

    public SetAgentInfoPayload getCurrentAgentInfo() {
        return currentAgentInfo;
    }

    public SetModelInfoPayload getCurrentModelInfo() {
        return currentModelInfo;
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
        this.branch = branch;
        this.branchEvents = new ArrayList<>(branchEvents);
        this.currentRun = null;
        this.statusRef.set(AgentStatus.idle);
        refreshProjection();
    }

    public SessionEventProjection projection() {
        return sessionEventMessageProjector.project(branchEvents);
    }

    protected SessionEvent appendEvent(SessionEventType eventType, Payload payload) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }

        String previousHeadEventId = branch.headEventId();
        SessionEvent event = SessionEvent.newEvent(session.getSessionId(), eventType, previousHeadEventId, payload);
        branch = sessionManager.appendEvent(branch, event);
        branchEvents.add(event);

        if (sessionManager.compareAndSetCurrentHeadEventId(session.getSessionId(), previousHeadEventId, branch.headEventId())) {
            session.setCurrentHeadEventId(branch.headEventId());
        }

        refreshProjection();
        agentEventHandler.onEvent(event);
        return event;
    }

    /**
     * 当队列非空且当前空闲时，尝试启动一次主 loop。
     */
    private void triggerMainLoop() {
        if (userRequestQueue.isEmpty()) {
            return;
        }
        if (!statusRef.compareAndSet(AgentStatus.idle, AgentStatus.busy)) {
            return;
        }
        enqueueSignal(StartLoopSignal.INSTANCE);
    }

    private void enqueueSignal(AgentSignal signal) {
        signalQueue.offer(signal);
        drainSignals();
    }

    /**
     * 串行消费全部待处理信号，确保 branch event 追加不并发。
     */
    private void drainSignals() {
        if (!drainingSignals.compareAndSet(false, true)) {
            return;
        }
        try {
            AgentSignal signal;
            while ((signal = signalQueue.poll()) != null) {
                handleSignal(signal);
            }
        } finally {
            drainingSignals.set(false);
            if (!signalQueue.isEmpty()) {
                drainSignals();
            }
        }
    }

    private void handleSignal(AgentSignal signal) {
        if (signal instanceof StartLoopSignal) {
            onStartLoop();
        } else if (signal instanceof RetryAssistantSignal retryAssistantSignal) {
            onRetryAssistant(retryAssistantSignal);
        } else if (signal instanceof AbortSignal abortSignal) {
            onAbort(abortSignal);
        } else if (signal instanceof AssistantTextDeltaSignal textDeltaSignal) {
            onAssistantTextDelta(textDeltaSignal);
        } else if (signal instanceof AssistantThinkingDeltaSignal thinkingDeltaSignal) {
            onAssistantThinkingDelta(thinkingDeltaSignal);
        } else if (signal instanceof AssistantToolCallDeltaSignal toolCallDeltaSignal) {
            onAssistantToolCallDelta(toolCallDeltaSignal);
        } else if (signal instanceof AssistantToolCallCompleteSignal toolCallCompleteSignal) {
            onAssistantToolCallComplete(toolCallCompleteSignal);
        } else if (signal instanceof AssistantCompleteSignal assistantCompleteSignal) {
            onAssistantComplete(assistantCompleteSignal);
        } else if (signal instanceof AssistantErrorSignal assistantErrorSignal) {
            onAssistantError(assistantErrorSignal);
        } else if (signal instanceof ToolPartialSignal toolPartialSignal) {
            onToolPartial(toolPartialSignal);
        } else if (signal instanceof ToolCompleteSignal toolCompleteSignal) {
            onToolComplete(toolCompleteSignal);
        } else if (signal instanceof ToolErrorSignal toolErrorSignal) {
            onToolError(toolErrorSignal);
        }
    }

    /**
     * 启动一轮新的主 loop。
     */
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
        currentRun.retryCount = 0;
        startAssistantAttempt(userMessages);
    }

    private void onRetryAssistant(RetryAssistantSignal signal) {
        if (currentRun != signal.runContext() || currentRun == null || currentRun.aborted) {
            return;
        }
        currentRun.scheduledTask = null;
        startAssistantAttempt(List.of());
    }

    private void startAssistantAttempt(List<String> userMessages) {
        if (currentRun == null || currentRun.aborted) {
            return;
        }

        AgentRuntimeConfigResolver.ResolvedRuntimeConfig runtimeConfig = resolveRuntimeConfig();
        AssistantStartPayload assistantStartPayload = new AssistantStartPayload();
        assistantStartPayload.setUserMessages(userMessages);
        appendEvent(SessionEventType.assistant_start, assistantStartPayload);

        AssistantAttemptState attemptState = new AssistantAttemptState(currentRun);
        currentRun.activeAssistant = attemptState;

        try {
            AssistantResponseHandle handle = runtimeConfig.getProvider().asyncChat(projectedMessages, runtimeConfig.getModelRequestConfig(), new AssistantResponseHandler() {
                @Override
                public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantTextDeltaSignal(attemptState, textDelta));
                }

                @Override
                public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantThinkingDeltaSignal(attemptState, thinkingDelta));
                }

                @Override
                public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantToolCallDeltaSignal(attemptState, toolCallDelta));
                }

                @Override
                public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantToolCallCompleteSignal(attemptState, index, toolCall));
                }

                @Override
                public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantCompleteSignal(attemptState, response));
                }

                @Override
                public void onError(Throwable error, AssistantResponseHandle handle) {
                    enqueueSignal(new AssistantErrorSignal(attemptState, error));
                }
            });
            attemptState.handle = handle;
        } catch (Throwable error) {
            enqueueSignal(new AssistantErrorSignal(attemptState, error));
        }
    }

    private void onAssistantTextDelta(AssistantTextDeltaSignal signal) {
        if (!isActiveAssistant(signal.attemptState()) || signal.textDelta() == null || signal.textDelta().isEmpty()) {
            return;
        }
        signal.attemptState().text.append(signal.textDelta());
        AssistantDeltaPayload payload = new AssistantDeltaPayload();
        payload.setTextDelta(signal.textDelta());
        appendEvent(SessionEventType.assistant_delta, payload);
    }

    private void onAssistantThinkingDelta(AssistantThinkingDeltaSignal signal) {
        if (!isActiveAssistant(signal.attemptState()) || signal.thinkingDelta() == null || signal.thinkingDelta().isEmpty()) {
            return;
        }
        signal.attemptState().thinking.append(signal.thinkingDelta());
        AssistantDeltaPayload payload = new AssistantDeltaPayload();
        payload.setThinkingDelta(signal.thinkingDelta());
        appendEvent(SessionEventType.assistant_delta, payload);
    }

    private void onAssistantToolCallDelta(AssistantToolCallDeltaSignal signal) {
        if (!isActiveAssistant(signal.attemptState()) || signal.toolCallDelta() == null) {
            return;
        }
        signal.attemptState().applyToolCallDelta(signal.toolCallDelta());
        AssistantDeltaPayload payload = new AssistantDeltaPayload();
        payload.setToolCallsDelta(List.of(signal.toolCallDelta()));
        appendEvent(SessionEventType.assistant_delta, payload);
    }

    private void onAssistantToolCallComplete(AssistantToolCallCompleteSignal signal) {
        if (!isActiveAssistant(signal.attemptState()) || signal.index() == null || signal.toolCall() == null) {
            return;
        }
        List<IndexedToolCallDelta> gap = signal.attemptState().computeToolCallGap(signal.index(), signal.toolCall());
        if (!gap.isEmpty()) {
            AssistantDeltaPayload payload = new AssistantDeltaPayload();
            payload.setToolCallsDelta(gap);
            appendEvent(SessionEventType.assistant_delta, payload);
        }
    }

    /**
     * 处理 assistant 完整结束，并决定进入 tool 阶段还是直接结束本轮 loop。
     */
    private void onAssistantComplete(AssistantCompleteSignal signal) {
        if (!isActiveAssistant(signal.attemptState())) {
            return;
        }
        currentRun.activeAssistant = null;
        signal.attemptState().handle = null;

        AssistantResponse response = signal.response() == null ? AssistantResponse.builder().build() : signal.response();
        appendAssistantCompletionGap(signal.attemptState(), response);
        AssistantEndPayload assistantEndPayload = new AssistantEndPayload();
        assistantEndPayload.setMetadata(response.getMetadata());
        appendEvent(SessionEventType.assistant_end, assistantEndPayload);

        currentRun.retryCount = 0;
        List<ToolCall> toolCalls = response.getToolCalls() == null ? List.of() : response.getToolCalls();
        if (toolCalls.isEmpty()) {
            finishAfterFinalAnswer();
            return;
        }
        startToolBatch(toolCalls);
    }

    private void onAssistantError(AssistantErrorSignal signal) {
        if (!isActiveAssistant(signal.attemptState())) {
            return;
        }
        currentRun.activeAssistant = null;
        signal.attemptState().handle = null;

        appendEvent(SessionEventType.error, newErrorPayload(null, toErrorMessage(signal.error())));
        if (currentRun.aborted) {
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
        currentRun.scheduledTask = agentScheduler.schedule(delay, () -> enqueueSignal(new RetryAssistantSignal(runContext)));
    }

    /**
     * 启动当前 assistant 产出的整批 tool call。
     */
    private void startToolBatch(List<ToolCall> toolCalls) {
        if (currentRun == null || currentRun.aborted) {
            return;
        }
        currentRun.toolStates.clear();
        for (ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            ToolStartPayload toolStartPayload = new ToolStartPayload();
            toolStartPayload.setToolCallId(toolCall.getToolCallId());
            toolStartPayload.setToolName(toolCall.getToolName());
            toolStartPayload.setArguments(toolCall.getArguments());
            appendEvent(SessionEventType.tool_start, toolStartPayload);

            ToolExecutionState toolState = new ToolExecutionState(currentRun, toolCall);
            currentRun.toolStates.put(toolCall.getToolCallId(), toolState);
            Tool tool = toolRegistry.getTool(toolCall.getToolName());
            if (tool == null) {
                appendEvent(SessionEventType.error, newErrorPayload(toolCall.getToolCallId(), "tool not found: " + toolCall.getToolName()));
                toolState.closed = true;
                continue;
            }
            ToolExecutionRequest request = toolCallMapper.toToolExecutionRequest(toolCall);
            try {
                ToolExecutionHandle handle = tool.asyncExecute(request, new ToolExecutionHandler() {
                    @Override
                    public void onPartial(List<IndexedToolContentDelta> partial, ToolExecutionContext ignored) {
                        enqueueSignal(new ToolPartialSignal(toolState, partial));
                    }

                    @Override
                    public void onComplete(List<ToolContent> result, ToolExecutionContext ignored) {
                        enqueueSignal(new ToolCompleteSignal(toolState, result));
                    }

                    @Override
                    public void onError(Throwable error, ToolExecutionContext ignored) {
                        enqueueSignal(new ToolErrorSignal(toolState, error));
                    }
                });
                toolState.handle = handle;
            } catch (Throwable error) {
                appendEvent(SessionEventType.error, newErrorPayload(toolCall.getToolCallId(), toErrorMessage(error)));
                toolState.closed = true;
            }
        }
        if (allToolsClosed()) {
            startAssistantAttempt(harvestUserMessages());
        }
    }

    private void onToolPartial(ToolPartialSignal signal) {
        if (!isActiveTool(signal.toolState()) || signal.contentDeltas() == null || signal.contentDeltas().isEmpty()) {
            return;
        }
        signal.toolState().applyContentDeltas(signal.contentDeltas());
        ToolDeltaPayload toolDeltaPayload = new ToolDeltaPayload();
        toolDeltaPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
        toolDeltaPayload.setContentDeltas(signal.contentDeltas());
        appendEvent(SessionEventType.tool_delta, toolDeltaPayload);
    }

    private void onToolComplete(ToolCompleteSignal signal) {
        if (!isActiveTool(signal.toolState())) {
            return;
        }
        signal.toolState().handle = null;
        List<IndexedToolContentDelta> gap = signal.toolState().computeGap(signal.contents());
        if (!gap.isEmpty()) {
            ToolDeltaPayload toolDeltaPayload = new ToolDeltaPayload();
            toolDeltaPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
            toolDeltaPayload.setContentDeltas(gap);
            appendEvent(SessionEventType.tool_delta, toolDeltaPayload);
        }
        ToolEndPayload toolEndPayload = new ToolEndPayload();
        toolEndPayload.setToolCallId(signal.toolState().toolCall.getToolCallId());
        appendEvent(SessionEventType.tool_end, toolEndPayload);
        signal.toolState().closed = true;
        maybeContinueAfterToolBatch();
    }

    private void onToolError(ToolErrorSignal signal) {
        if (!isActiveTool(signal.toolState())) {
            return;
        }
        signal.toolState().handle = null;
        appendEvent(SessionEventType.error, newErrorPayload(signal.toolState().toolCall.getToolCallId(), toErrorMessage(signal.error())));
        signal.toolState().closed = true;
        maybeContinueAfterToolBatch();
    }

    private void maybeContinueAfterToolBatch() {
        if (currentRun == null || currentRun.aborted || !allToolsClosed()) {
            return;
        }
        currentRun.retryCount = 0;
        startAssistantAttempt(harvestUserMessages());
    }

    /**
     * 处理当前 loop 的显式取消。
     */
    private void onAbort(AbortSignal signal) {
        if (currentRun == null) {
            return;
        }
        currentRun.aborted = true;
        if (currentRun.scheduledTask != null) {
            currentRun.scheduledTask.cancel();
            currentRun.scheduledTask = null;
        }
        if (currentRun.activeAssistant != null) {
            if (currentRun.activeAssistant.handle != null) {
                currentRun.activeAssistant.handle.cancel();
            }
            appendEvent(SessionEventType.abort, newAbortPayload(null, signal.reason()));
            currentRun.activeAssistant = null;
            releaseLoop();
            return;
        }
        for (ToolExecutionState toolState : currentRun.toolStates.values()) {
            if (toolState.closed) {
                continue;
            }
            if (toolState.handle != null) {
                toolState.handle.cancel();
            }
            appendEvent(SessionEventType.abort, newAbortPayload(toolState.toolCall.getToolCallId(), signal.reason()));
            toolState.closed = true;
        }
        releaseLoop();
    }

    private void finishAfterFinalAnswer() {
        releaseLoop();
    }

    private void releaseLoop() {
        currentRun = null;
        statusRef.set(AgentStatus.idle);
        if (!userRequestQueue.isEmpty()) {
            triggerMainLoop();
        }
    }

    /**
     * 在每次实际 assistant 调用前刷新最新运行配置，并在变化时落配置事件。
     */
    private AgentRuntimeConfigResolver.ResolvedRuntimeConfig resolveRuntimeConfig() {
        AgentRuntimeConfigResolver.ResolvedRuntimeConfig runtimeConfig = runtimeConfigResolver.resolve(agentName, provider, model, variant);
        SetAgentInfoPayload latestAgentPayload = runtimeConfig.getAgentPayload();
        if (!Objects.equals(latestAgentPayload, currentAgentInfo)) {
            appendEvent(SessionEventType.set_agent_info, latestAgentPayload);
            currentAgentInfo = latestAgentPayload;
        }

        SetModelInfoPayload latestModelPayload = runtimeConfig.getModelPayload();
        if (!Objects.equals(latestModelPayload, currentModelInfo)) {
            appendEvent(SessionEventType.set_model_info, latestModelPayload);
            currentModelInfo = latestModelPayload;
        }
        this.provider = runtimeConfig.getResolvedProvider();
        this.model = runtimeConfig.getResolvedModel();
        this.variant = runtimeConfig.getResolvedVariant();
        return runtimeConfig;
    }

    private void appendAssistantCompletionGap(AssistantAttemptState attemptState, AssistantResponse response) {
        List<IndexedToolCallDelta> toolCallGaps = attemptState.computeToolCallGaps(response.getToolCalls());
        String textGap = AgentTextDelta.gap(attemptState.text.toString(), response.getText());
        String thinkingGap = AgentTextDelta.gap(attemptState.thinking.toString(), response.getThinking());
        if ((textGap == null || textGap.isEmpty())
            && (thinkingGap == null || thinkingGap.isEmpty())
            && toolCallGaps.isEmpty()) {
            return;
        }
        AssistantDeltaPayload payload = new AssistantDeltaPayload();
        if (textGap != null && !textGap.isEmpty()) {
            attemptState.text.append(textGap);
            payload.setTextDelta(textGap);
        }
        if (thinkingGap != null && !thinkingGap.isEmpty()) {
            attemptState.thinking.append(thinkingGap);
            payload.setThinkingDelta(thinkingGap);
        }
        if (!toolCallGaps.isEmpty()) {
            payload.setToolCallsDelta(toolCallGaps);
        }
        appendEvent(SessionEventType.assistant_delta, payload);
    }

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

    private boolean allToolsClosed() {
        if (currentRun == null) {
            return true;
        }
        for (ToolExecutionState toolState : currentRun.toolStates.values()) {
            if (!toolState.closed) {
                return false;
            }
        }
        return true;
    }

    private boolean isActiveAssistant(AssistantAttemptState attemptState) {
        return currentRun != null && currentRun.activeAssistant == attemptState && !currentRun.aborted;
    }

    private boolean isActiveTool(ToolExecutionState toolState) {
        return currentRun != null
            && currentRun.toolStates.get(toolState.toolCall.getToolCallId()) == toolState
            && !toolState.closed
            && !currentRun.aborted;
    }

    private ErrorPayload newErrorPayload(String toolCallId, String message) {
        ErrorPayload payload = new ErrorPayload();
        payload.setToolCallId(toolCallId);
        payload.setMessage(message);
        return payload;
    }

    private AbortPayload newAbortPayload(String toolCallId, String reason) {
        AbortPayload payload = new AbortPayload();
        payload.setToolCallId(toolCallId);
        payload.setReason(reason);
        return payload;
    }

    private String toErrorMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void refreshProjection() {
        SessionEventProjection projection = projection();
        if (projection.agentInfo() != null) {
            this.currentAgentInfo = projection.agentInfo();
        }
        if (projection.modelInfo() != null) {
            this.currentModelInfo = projection.modelInfo();
        }
        this.projectedMessages = projection.messages();
    }

    private <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

}
