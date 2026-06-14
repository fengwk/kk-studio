package fun.fengwk.kkstudio.core.agent.runtime.engine;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import fun.fengwk.convention4j.common.util.CollectionUtils;
import fun.fengwk.convention4j.common.util.NullSafe;
import fun.fengwk.convention4j.springboot.starter.transaction.TransactionExecutor;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStatus;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.AbortEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.ErrorEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.EventType;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.MessageDeltaEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.MessageDeltaType;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.MessageEndEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.MessageStartEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.MessageToolCallEndEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.ToolCallDeltaEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.ToolCallEndEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.ToolCallStartEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.TurnEndEvent;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.TurnStartEvent;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.Provider;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderManager;
import fun.fengwk.kkstudio.core.agent.runtime.recovery.AgentBusyRecoveryChecker;
import fun.fengwk.kkstudio.core.agent.runtime.recovery.AgentBusyRecoveryHandler;
import fun.fengwk.kkstudio.core.agent.runtime.repo.AgentRequestTaskRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventSessionRepository;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallEngine;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallHandle;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallListener;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Submit-driven、事件溯源的 agent runtime。
 * <p>
 * 设计目标：服务节点保持无状态，所有跨节点互斥依赖 session head CAS；submit 只负责写入任务并尝试触发
 * 一条异步执行链，执行失败只显式暴露给上层，不在内核中自动恢复或重试。
 *
 * @author fengwk
 */
@Slf4j
@Component
@ConditionalOnBean({EventSessionRepository.class, EventRepository.class, AgentRequestTaskRepository.class, ToolCallEngine.class})
public class AgentRuntimeEngineImpl implements AgentRuntimeEngine, AgentBusyRecoveryHandler {

    /** 单个模型 turn 只消费一个用户提交，避免不同提交的模型配置/system prompt 混用。 */
    private static final int USER_TURN_TASK_LIMIT = 1;

    private final EventSessionRepository eventSessionRepository;
    private final EventRepository eventRepository;
    private final AgentRequestTaskRepository agentRequestTaskRepository;
    private final TransactionExecutor transactionExecutor;
    private final ProviderManager providerManager;
    private final ToolCallEngine toolCallEngine;
    private final ObjectProvider<AgentBusyRecoveryChecker> busyRecoveryCheckerProvider;
    private final ConcurrentHashMap<String, ModelStreamHandler> activeHandlerBySessionId = new ConcurrentHashMap<>();

    public AgentRuntimeEngineImpl(EventSessionRepository eventSessionRepository,
                                  EventRepository eventRepository,
                                  AgentRequestTaskRepository agentRequestTaskRepository,
                                  TransactionExecutor transactionExecutor,
                                  ProviderManager providerManager,
                                  ToolCallEngine toolCallEngine,
                                  ObjectProvider<AgentBusyRecoveryChecker> busyRecoveryCheckerProvider) {
        this.eventSessionRepository = Objects.requireNonNull(eventSessionRepository, "eventSessionRepository must not be null");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository must not be null");
        this.agentRequestTaskRepository = Objects.requireNonNull(agentRequestTaskRepository, "agentRequestTaskRepository must not be null");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor must not be null");
        this.providerManager = Objects.requireNonNull(providerManager, "providerManager must not be null");
        this.toolCallEngine = Objects.requireNonNull(toolCallEngine, "toolCallEngine must not be null");
        this.busyRecoveryCheckerProvider = Objects.requireNonNull(busyRecoveryCheckerProvider,
            "busyRecoveryCheckerProvider must not be null");
    }

    @Override
    public String newSession() {
        EventSession eventSession = eventSessionRepository.newSession(null);
        log.info("agent session created, sessionId: {}", eventSession.getSessionId());
        return eventSession.getSessionId();
    }

    @Override
    public String fork(String headEventId) {
        EventSession eventSession = eventSessionRepository.newSession(headEventId);
        log.info("agent session forked, sessionId: {}, headEventId: {}", eventSession.getSessionId(), headEventId);
        return eventSession.getSessionId();
    }

    @Override
    public String submit(AgentRequest agentRequest) {
        Objects.requireNonNull(agentRequest, "agentRequest must not be null");
        validateAgentRequest(agentRequest);
        String sessionId = agentRequest.getSessionId();

        AgentRequestTask agentRequestTask = buildAgentRequestTask(agentRequest);
        agentRequestTask = agentRequestTaskRepository.add(agentRequestTask);
        log.info("agent task submitted, sessionId: {}, taskId: {}", sessionId, agentRequestTask.getTaskId());

        // submit 是自然触发点：抢到 head 的节点启动异步执行链，抢不到说明当前 session 已有链路在推进。
        boolean started = tryStartUserTurn(sessionId);
        if (!started) {
            started = handleQueuedSubmit(sessionId, agentRequest.isRecoverIfBusy(), agentRequest.getRecoverReason());
        }
        if (!started) {
            log.debug("agent task queued, sessionId: {}, taskId: {}", sessionId, agentRequestTask.getTaskId());
        }
        return agentRequestTask.getTaskId();
    }

    private boolean handleQueuedSubmit(String sessionId, boolean checkNow, String recoverReason) {
        AgentBusyRecoveryChecker busyRecoveryChecker = busyRecoveryCheckerProvider.getIfAvailable();
        if (busyRecoveryChecker == null) {
            return false;
        }
        if (checkNow) {
            return busyRecoveryChecker.checkNow(sessionId, recoverReason);
        }
        busyRecoveryChecker.onSubmitQueued(sessionId);
        return false;
    }

    @Override
    public boolean recoverAndContinue(String sessionId, String reason) {
        String recoveredTurnId = recoverBusySession(sessionId, reason);
        if (recoveredTurnId == null) {
            return false;
        }
        cancelActiveHandler(sessionId, recoveredTurnId, "busy session recovered");
        return tryStartUserTurn(sessionId);
    }

    @Override
    public boolean abort(String sessionId, String reason) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String abortedTurnId = abortBusySession(sessionId, reason);
        if (abortedTurnId != null) {
            cancelActiveHandler(sessionId, abortedTurnId, "session aborted");
            log.info("agent session aborted, sessionId: {}, reason: {}", sessionId, reason);
            return true;
        }
        log.debug("agent session abort ignored, sessionId: {}, reason: {}", sessionId, reason);
        return false;
    }

    private void cancelActiveHandler(String sessionId, String expectedTurnId, String reason) {
        ModelStreamHandler handler = activeHandlerBySessionId.get(sessionId);
        if (handler != null && Objects.equals(handler.getTurnId(), expectedTurnId)) {
            handler.cancelExternal(reason);
        }
    }

    private AgentRequestTask buildAgentRequestTask(AgentRequest agentRequest) {
        AgentRequestTask agentRequestTask = new AgentRequestTask();
        agentRequestTask.setTaskId(agentRequestTaskRepository.generateTaskId());
        agentRequestTask.setSessionId(agentRequest.getSessionId());
        agentRequestTask.setIdempotencyKey(agentRequest.getIdempotencyKey());
        agentRequestTask.setProviderConfig(agentRequest.getProviderConfig());
        agentRequestTask.setModelRequestConfig(agentRequest.getModelRequestConfig());
        agentRequestTask.setParameters(agentRequest.getParameters());
        agentRequestTask.setSystemPrompt(agentRequest.getSystemPrompt());
        agentRequestTask.setUserMessage(agentRequest.getUserMessage());
        agentRequestTask.setCreateTime(LocalDateTime.now());
        agentRequestTask.setConsume(false);
        return agentRequestTask;
    }

    private void validateAgentRequest(AgentRequest agentRequest) {
        String sessionId = Objects.requireNonNull(agentRequest.getSessionId(), "sessionId must not be null");
        if (eventSessionRepository.get(sessionId) == null) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        if (!isNotBlank(agentRequest.getUserMessage())) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        ProviderConfig providerConfig = Objects.requireNonNull(agentRequest.getProviderConfig(), "providerConfig must not be null");
        Objects.requireNonNull(providerConfig.getProviderType(), "providerType must not be null");
        ModelRequestConfig modelRequestConfig = Objects.requireNonNull(agentRequest.getModelRequestConfig(),
            "modelRequestConfig must not be null");
        if (!isNotBlank(modelRequestConfig.getModelName())) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
    }

    /**
     * 尝试从用户提交启动第一轮模型 turn。
     * <p>
     * 该方法不是调度器，只是 submit 或 turn_end 后的轻量接力门禁。真正的跨节点互斥发生在
     * {@link EventSessionRepository#casHeadEventId(String, String, String, EventSessionStatus, String)}。
     */
    private boolean tryStartUserTurn(String sessionId) {
        StartTurnResult startTurnResult;
        try {
            startTurnResult = prepareUserTurn(sessionId);
        } catch (ConcurrentTurnException ex) {
            log.debug("agent turn start conflicted before model call, sessionId: {}", sessionId, ex);
            return false;
        }

        if (!startTurnResult.isStarted()) {
            return false;
        }

        callModel(startTurnResult.getSessionId(), startTurnResult.getTurnId(), startTurnResult.getHeadEventId());
        return true;
    }

    /**
     * 在一个事务中完成：锁定 pending task、追加首个 turn_start/user_message、CAS 推进 head、更新配置并标记消费。
     */
    private StartTurnResult prepareUserTurn(String sessionId) {
        String turnStartEventId = eventRepository.generateEventId();
        String turnId = turnStartEventId;
        return transactionExecutor.executeWithRequired(() -> {
            EventSession eventSession = eventSessionRepository.get(sessionId);
            if (eventSession == null) {
                log.warn("agent turn start ignored, session not found, sessionId: {}", sessionId);
                return StartTurnResult.notStarted();
            }
            if (!isStartable(eventSession.getStatus())) {
                log.debug("agent turn start ignored, session is running, sessionId: {}, status: {}, headEventId: {}",
                    sessionId, eventSession.getStatus(), eventSession.getHeadEventId());
                return StartTurnResult.notStarted();
            }

            List<AgentRequestTask> taskList = agentRequestTaskRepository.listPendingForUpdate(
                sessionId, USER_TURN_TASK_LIMIT);
            if (CollectionUtils.isEmpty(taskList)) {
                log.debug("agent turn start ignored, no pending task, sessionId: {}", sessionId);
                return StartTurnResult.notStarted();
            }

            updateSessionConfigIfNecessary(sessionId, eventSession, taskList);

            String oldHeadEventId = eventSession.getHeadEventId();
            TurnStartEvent turnStartEvent = newEvent(new TurnStartEvent(), sessionId, turnId, turnStartEventId, oldHeadEventId);

            MessageStartEvent messageStartEvent = newEvent(
                new MessageStartEvent(), sessionId, turnId, eventRepository.generateEventId(), turnId);
            messageStartEvent.setTaskIdList(taskList.stream().map(AgentRequestTask::getTaskId).toList());
            messageStartEvent.setUserMessageList(taskList.stream()
                .map(AgentRequestTask::getUserMessage)
                .filter(Objects::nonNull)
                .toList());

            boolean headMoved = eventSessionRepository.casHeadEventId(
                sessionId, oldHeadEventId, messageStartEvent.getEventId(), EventSessionStatus.busy, turnId);
            if (!headMoved) {
                // 必须抛异常回滚本事务，释放 task 行锁并撤销本轮状态变更。
                throw new ConcurrentTurnException("CAS start turn failed, sessionId: " + sessionId);
            }

            eventRepository.append(turnStartEvent);
            eventRepository.append(messageStartEvent);

            boolean consumed = agentRequestTaskRepository.consumeAll(messageStartEvent.getTaskIdList(), turnId);
            if (!consumed) {
                throw new IllegalStateException("Consume task failed, sessionId: " + sessionId
                    + ", taskIds: " + messageStartEvent.getTaskIdList());
            }

            log.info("agent first turn started, sessionId: {}, turnId: {}, taskIds: {}, headEventId: {}",
                sessionId, turnId, messageStartEvent.getTaskIdList(), messageStartEvent.getEventId());
            return StartTurnResult.started(sessionId, turnId, messageStartEvent.getEventId());
        });
    }

    private void updateSessionConfigIfNecessary(String sessionId, EventSession eventSession, List<AgentRequestTask> taskList) {
        // 以本轮锁定到的最新提交为配置基准；旧配置仍通过历史 event 重建上下文。
        AgentRequestTask latestTask = taskList.get(taskList.size() - 1);
        ProviderConfig providerConfig = latestTask.getProviderConfig();
        ModelRequestConfig modelRequestConfig = latestTask.getModelRequestConfig();
        Map<String, Object> parameters = latestTask.getParameters();
        String systemPrompt = latestTask.getSystemPrompt();

        boolean configUpdated = false;
        if (!Objects.equals(eventSession.getProviderConfig(), providerConfig)) {
            eventSession.setProviderConfig(providerConfig);
            configUpdated = true;
        }
        if (!Objects.equals(eventSession.getModelRequestConfig(), modelRequestConfig)) {
            eventSession.setModelRequestConfig(modelRequestConfig);
            configUpdated = true;
        }
        if (!Objects.equals(eventSession.getParameters(), parameters)) {
            eventSession.setParameters(parameters);
            configUpdated = true;
        }
        if (!Objects.equals(eventSession.getSystemPrompt(), systemPrompt)) {
            eventSession.setSystemPrompt(systemPrompt);
            configUpdated = true;
        }
        if (configUpdated) {
            eventSessionRepository.updateSessionConfig(sessionId, providerConfig, modelRequestConfig, parameters, systemPrompt);
            log.debug("agent session config updated, sessionId: {}, providerType: {}, model: {}",
                sessionId,
                providerConfig == null ? null : providerConfig.getProviderType(),
                modelRequestConfig == null ? null : modelRequestConfig.getModelName());
        }
    }

    private boolean isStartable(EventSessionStatus status) {
        return status == null
            || status == EventSessionStatus.idle;
    }

    private boolean needsMessageEndBeforeError(String headEventId, String turnId) {
        Event event = headEventId == null ? null : eventRepository.get(headEventId);
        while (event != null && Objects.equals(event.getTurnId(), turnId)) {
            EventType eventType = event.getEventType();
            if (eventType == EventType.message_end) {
                return false;
            }
            if (eventType == EventType.message_delta || eventType == EventType.message_tool_call_end) {
                return true;
            }
            if (eventType == EventType.turn_start || eventType == EventType.message_start) {
                return false;
            }
            String parentEventId = event.getParentEventId();
            event = parentEventId == null ? null : eventRepository.get(parentEventId);
        }
        return false;
    }

    private void callModel(String sessionId, String turnId, String headEventId) {
        EventSession eventSession = eventSessionRepository.get(sessionId);
        if (eventSession == null) {
            log.warn("agent model call ignored, session not found, sessionId: {}, turnId: {}", sessionId, turnId);
            return;
        }

        ModelStreamHandler handler = null;
        try {
            List<ChatMessage> messageList = buildModelMessages(eventSession, headEventId);
            Provider provider = providerManager.getProvider(eventSession.getProviderConfig());
            ChatRequest chatRequest = provider.buildChatRequest(messageList, eventSession.getModelRequestConfig());
            StreamingChatModel chatModel = provider.getChatModel();

            log.info("agent model call started, sessionId: {}, turnId: {}, headEventId: {}, messageCount: {}",
                sessionId, turnId, headEventId, messageList.size());
            // LangChain4j streaming model 通常以 callback 形式异步返回。
            // TODO 若后续接入的 provider.chat 会阻塞提交线程，应在 provider adapter 层封装异步执行器。
            handler = new ModelStreamHandler(sessionId, turnId, headEventId);
            activeHandlerBySessionId.put(sessionId, handler);
            chatModel.chat(chatRequest, handler);
        } catch (Throwable error) {
            if (handler != null) {
                activeHandlerBySessionId.remove(sessionId, handler);
            }
            log.error("agent model call failed before streaming callback, sessionId: {}, turnId: {}, headEventId: {}",
                sessionId, turnId, headEventId, error);
            appendErrorAndTurnEnd(sessionId, turnId, headEventId, error);
        }
    }

    /**
     * 显式恢复 busy session。
     * <p>
     * 该方法只响应上层/用户请求，不做后台自动恢复；用于节点崩溃等非受控停止后，闭合未完成 turn 并释放 session。
     */
    private String recoverBusySession(String sessionId, String reason) {
        return transactionExecutor.executeWithRequired(() -> {
            EventSession eventSession = eventSessionRepository.get(sessionId);
            if (eventSession == null || eventSession.getStatus() != EventSessionStatus.busy) {
                return null;
            }

            String parentEventId = eventSession.getHeadEventId();
            Event headEvent = parentEventId == null ? null : eventRepository.get(parentEventId);
            String turnId = headEvent == null ? eventSession.getRunningTurnId() : headEvent.getTurnId();
            if (turnId == null) {
                log.warn("agent busy recovery ignored, turnId missing, sessionId: {}, headEventId: {}", sessionId, parentEventId);
                return null;
            }

            if (needsMessageEndBeforeError(parentEventId, turnId)) {
                MessageEndEvent messageEndEvent = newEvent(
                    new MessageEndEvent(), sessionId, turnId, eventRepository.generateEventId(), parentEventId);
                if (!eventSessionRepository.casHeadEventId(
                    sessionId, parentEventId, messageEndEvent.getEventId(), EventSessionStatus.busy, turnId)) {
                    return null;
                }
                eventRepository.append(messageEndEvent);
                parentEventId = messageEndEvent.getEventId();
            }

            String recoverMessage = isNotBlank(reason) ? reason : "Session recovered by user submit";
            IllegalStateException recoveryError = new IllegalStateException(recoverMessage);
            parentEventId = appendMissingToolResultErrorsForRecovery(sessionId, turnId, parentEventId, recoveryError);
            String turnEndEventId = appendErrorAndTurnEndInCurrentTransaction(sessionId, turnId, parentEventId, recoveryError);
            log.warn("agent busy session recovered by submit, sessionId: {}, turnId: {}, turnEndEventId: {}",
                sessionId, turnId, turnEndEventId);
            return turnId;
        });
    }

    private String abortBusySession(String sessionId, String reason) {
        return transactionExecutor.executeWithRequired(() -> {
            EventSession eventSession = eventSessionRepository.get(sessionId);
            if (eventSession == null || eventSession.getStatus() != EventSessionStatus.busy) {
                return null;
            }

            String parentEventId = eventSession.getHeadEventId();
            Event headEvent = parentEventId == null ? null : eventRepository.get(parentEventId);
            String turnId = headEvent == null ? eventSession.getRunningTurnId() : headEvent.getTurnId();
            if (turnId == null) {
                log.warn("agent abort ignored, turnId missing, sessionId: {}, headEventId: {}", sessionId, parentEventId);
                return null;
            }

            if (needsMessageEndBeforeError(parentEventId, turnId)) {
                MessageEndEvent messageEndEvent = newEvent(
                    new MessageEndEvent(), sessionId, turnId, eventRepository.generateEventId(), parentEventId);
                if (!eventSessionRepository.casHeadEventId(
                    sessionId, parentEventId, messageEndEvent.getEventId(), EventSessionStatus.busy, turnId)) {
                    return null;
                }
                eventRepository.append(messageEndEvent);
                parentEventId = messageEndEvent.getEventId();
            }

            String abortReason = isNotBlank(reason) ? reason : "Session aborted by user";
            IllegalStateException abortError = new IllegalStateException(abortReason);
            parentEventId = appendMissingToolResultErrorsForRecovery(sessionId, turnId, parentEventId, abortError);
            String turnEndEventId = appendAbortAndTurnEndInCurrentTransaction(sessionId, turnId, parentEventId, abortReason);
            log.warn("agent busy session aborted, sessionId: {}, turnId: {}, turnEndEventId: {}",
                sessionId, turnId, turnEndEventId);
            return turnId;
        });
    }

    private String appendMissingToolResultErrorsForRecovery(String sessionId, String turnId, String parentEventId,
                                                            Throwable error) {
        List<Event> events = backtrace(parentEventId);
        Collections.reverse(events);

        TreeMap<Integer, MessageToolCallEndEvent> toolCallByIndex = new TreeMap<>();
        Set<String> completedToolCallIds = new HashSet<>();
        for (Event event : events) {
            if (!Objects.equals(event.getTurnId(), turnId)) {
                continue;
            }
            if (event.getEventType() == EventType.message_tool_call_end
                && event instanceof MessageToolCallEndEvent messageToolCallEndEvent
                && messageToolCallEndEvent.getIndex() != null) {
                toolCallByIndex.put(messageToolCallEndEvent.getIndex(), messageToolCallEndEvent);
            } else if (event.getEventType() == EventType.tool_call_end
                && event instanceof ToolCallEndEvent toolCallEndEvent
                && toolCallEndEvent.getId() != null) {
                completedToolCallIds.add(toolCallEndEvent.getId());
            }
        }

        String currentParentEventId = parentEventId;
        String errorMessage = buildErrorMessage(error);
        for (MessageToolCallEndEvent messageToolCallEndEvent : toolCallByIndex.values()) {
            String toolCallId = messageToolCallEndEvent.getId();
            if (toolCallId != null && completedToolCallIds.contains(toolCallId)) {
                continue;
            }

            ToolCallEndEvent toolCallEndEvent = newEvent(
                new ToolCallEndEvent(), sessionId, turnId, eventRepository.generateEventId(), currentParentEventId);
            toolCallEndEvent.setId(toolCallId);
            toolCallEndEvent.setResult(errorMessage);
            toolCallEndEvent.setError(true);
            if (!eventSessionRepository.casHeadEventId(
                sessionId, currentParentEventId, toolCallEndEvent.getEventId(), EventSessionStatus.busy, turnId)) {
                throw new ConcurrentTurnException("CAS append recovery tool error failed, sessionId: " + sessionId);
            }
            eventRepository.append(toolCallEndEvent);
            currentParentEventId = toolCallEndEvent.getEventId();
        }
        return currentParentEventId;
    }

    private List<ChatMessage> buildModelMessages(EventSession eventSession, String headEventId) {
        List<Event> eventList = backtrace(headEventId);
        List<ChatMessage> historyMessages = buildChatMessageList(eventList);

        List<ChatMessage> messageList = new ArrayList<>();
        if (isNotBlank(eventSession.getSystemPrompt())) {
            messageList.add(SystemMessage.systemMessage(eventSession.getSystemPrompt()));
        }
        messageList.addAll(historyMessages);
        return messageList;
    }

    private final class ModelStreamHandler implements StreamingChatResponseHandler {

        private final String sessionId;
        private final String turnId;
        private final AtomicReference<String> latestHeadEventId;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        private final AtomicReference<ToolCallHandle> toolCallHandle = new AtomicReference<>(ToolCallHandle.NOOP);
        private final StringBuilder textBuilder = new StringBuilder();
        private final StringBuilder thinkingBuilder = new StringBuilder();
        private final TreeMap<Integer, ToolCallRequest> toolCallRequestByIndex = new TreeMap<>();
        private final Set<String> completedToolCallIds = new HashSet<>();
        private boolean messageCompleted;
        private boolean toolExecutionCompleted;

        /**
         * LangChain4j 不保证所有 callback 在同一线程回调；使用本地锁保证单条执行链内的事件顺序。
         * 跨节点互斥仍由 session head CAS 负责。
         */
        private final Object callbackLock = new Object();

        private ModelStreamHandler(String sessionId, String turnId, String headEventId) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.latestHeadEventId = new AtomicReference<>(headEventId);
        }

        private String getTurnId() {
            return turnId;
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            rememberStreamingHandle(context == null ? null : context.streamingHandle());
            appendMessageDelta(MessageDeltaType.thinking, partialThinking == null ? null : partialThinking.text(), null);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            rememberStreamingHandle(context == null ? null : context.streamingHandle());
            appendMessageDelta(MessageDeltaType.text, partialResponse == null ? null : partialResponse.text(), null);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            rememberStreamingHandle(context == null ? null : context.streamingHandle());
            if (partialToolCall == null) {
                return;
            }
            appendMessageDelta(MessageDeltaType.tool_call, null, partialToolCall);
        }

        private void rememberStreamingHandle(StreamingHandle handle) {
            if (handle != null) {
                streamingHandle.compareAndSet(null, handle);
                if (cancelled.get()) {
                    cancelStreamingHandle(handle, "callback already cancelled");
                }
            }
        }

        private void appendMessageDelta(MessageDeltaType deltaType, String partialText, PartialToolCall partialToolCall) {
            if (cancelled.get()) {
                return;
            }
            synchronized (callbackLock) {
                if (cancelled.get() || messageCompleted) {
                    return;
                }
                appendMessageDeltaLocked(deltaType, partialText, partialToolCall, false);
            }
        }

        private boolean appendMessageDeltaLocked(MessageDeltaType deltaType, String partialText, PartialToolCall partialToolCall,
                                                 boolean replace) {
            MessageDeltaEvent messageDeltaEvent = newEvent(
                new MessageDeltaEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
            messageDeltaEvent.setType(deltaType);
            messageDeltaEvent.setReplace(replace);
            messageDeltaEvent.setPartialText(partialText);
            if (partialToolCall != null) {
                messageDeltaEvent.setIndex(partialToolCall.index());
                messageDeltaEvent.setId(partialToolCall.id());
                messageDeltaEvent.setName(partialToolCall.name());
                messageDeltaEvent.setPartialArguments(partialToolCall.partialArguments());
            }

            if (!appendRunningEvent(messageDeltaEvent)) {
                cancelByConflict("append message delta failed");
                return false;
            }
            latestHeadEventId.set(messageDeltaEvent.getEventId());
            if (deltaType == MessageDeltaType.text && partialText != null) {
                if (replace) {
                    textBuilder.setLength(0);
                }
                textBuilder.append(partialText);
            } else if (deltaType == MessageDeltaType.thinking && partialText != null) {
                if (replace) {
                    thinkingBuilder.setLength(0);
                }
                thinkingBuilder.append(partialText);
            }
            log.debug("agent message delta appended, sessionId: {}, turnId: {}, type: {}, eventId: {}",
                sessionId, turnId, deltaType, messageDeltaEvent.getEventId());
            return true;
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            if (completeToolCall == null || cancelled.get()) {
                return;
            }
            synchronized (callbackLock) {
                if (cancelled.get() || messageCompleted) {
                    return;
                }
                appendMessageToolCallEndLocked(completeToolCall.index(), convert(completeToolCall.toolExecutionRequest()), false);
            }
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            if (cancelled.get()) {
                return;
            }

            AiMessage aiMessage = completeResponse == null ? null : completeResponse.aiMessage();
            if (aiMessage == null) {
                onError(new IllegalStateException("Complete response has no AiMessage"));
                return;
            }

            List<ToolCallRequest> fallbackToolCallRequests = convert(aiMessage.toolExecutionRequests());
            List<ToolCallRequest> toolCallRequests;
            synchronized (callbackLock) {
                if (cancelled.get()) {
                    return;
                }

                if (!appendFinalTextDeltaIfNecessary(MessageDeltaType.text, aiMessage.text(), textBuilder)) {
                    return;
                }
                if (!appendFinalTextDeltaIfNecessary(MessageDeltaType.thinking, aiMessage.thinking(), thinkingBuilder)) {
                    return;
                }
                for (int i = 0; i < fallbackToolCallRequests.size(); i++) {
                    if (!appendMessageToolCallEndLocked(i, fallbackToolCallRequests.get(i), true)) {
                        return;
                    }
                }
                toolCallRequests = getCompletedToolCallRequestsInOrder();

                MessageEndEvent messageEndEvent = newEvent(
                    new MessageEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                messageEndEvent.setAttributes(aiMessage.attributes());
                if (!appendRunningEvent(messageEndEvent)) {
                    cancelByConflict("append message end failed");
                    return;
                }
                latestHeadEventId.set(messageEndEvent.getEventId());
                messageCompleted = true;
                log.info("agent message completed, sessionId: {}, turnId: {}, eventId: {}, toolCallCount: {}",
                    sessionId, turnId, messageEndEvent.getEventId(), toolCallRequests.size());
            }

            if (CollectionUtils.isEmpty(toolCallRequests)) {
                // 无 tool call 表示当前 agent run 已得到 final answer。
                finishTurnAndTryContinuePendingTask();
            } else {
                startToolCalls(toolCallRequests);
            }
        }

        private boolean appendFinalTextDeltaIfNecessary(MessageDeltaType deltaType, String finalText, StringBuilder currentBuilder) {
            if (finalText == null) {
                return true;
            }

            String currentText = currentBuilder.toString();
            if (finalText.equals(currentText)) {
                return true;
            }
            if (finalText.startsWith(currentText)) {
                String missingText = finalText.substring(currentText.length());
                return missingText.isEmpty() || appendMessageDeltaLocked(deltaType, missingText, null, false);
            }

            log.warn("agent message delta mismatch, append replacement delta, sessionId: {}, turnId: {}, type: {}",
                sessionId, turnId, deltaType);
            return appendMessageDeltaLocked(deltaType, finalText, null, true);
        }

        private boolean appendMessageToolCallEndLocked(Integer index, ToolCallRequest toolCallRequest, boolean overwrite) {
            if (index == null || toolCallRequest == null) {
                return false;
            }
            ToolCallRequest existingToolCallRequest = toolCallRequestByIndex.get(index);
            if (existingToolCallRequest != null && (!overwrite || Objects.equals(existingToolCallRequest, toolCallRequest))) {
                return true;
            }

            MessageToolCallEndEvent messageToolCallEndEvent = newEvent(
                new MessageToolCallEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
            messageToolCallEndEvent.setIndex(index);
            messageToolCallEndEvent.setId(toolCallRequest.getId());
            messageToolCallEndEvent.setName(toolCallRequest.getName());
            messageToolCallEndEvent.setArguments(toolCallRequest.getArguments());
            if (!appendRunningEvent(messageToolCallEndEvent)) {
                cancelByConflict("append message tool call end failed");
                return false;
            }

            latestHeadEventId.set(messageToolCallEndEvent.getEventId());
            toolCallRequestByIndex.put(index, toolCallRequest);
            log.debug("agent message tool call completed, sessionId: {}, turnId: {}, toolCallId: {}, eventId: {}",
                sessionId, turnId, toolCallRequest.getId(), messageToolCallEndEvent.getEventId());
            return true;
        }

        private List<ToolCallRequest> getCompletedToolCallRequestsInOrder() {
            return new ArrayList<>(toolCallRequestByIndex.values());
        }

        private void startToolCalls(List<ToolCallRequest> toolCallRequests) {
            synchronized (callbackLock) {
                if (cancelled.get()) {
                    return;
                }

                for (ToolCallRequest toolCallRequest : toolCallRequests) {
                    ToolCallStartEvent toolCallStartEvent = newEvent(
                        new ToolCallStartEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                    toolCallStartEvent.setId(toolCallRequest.getId());
                    toolCallStartEvent.setName(toolCallRequest.getName());
                    toolCallStartEvent.setArguments(toolCallRequest.getArguments());
                    if (!appendRunningEvent(toolCallStartEvent)) {
                        cancelByConflict("append tool call start failed");
                        return;
                    }
                    latestHeadEventId.set(toolCallStartEvent.getEventId());
                }
            }

            try {
                log.info("agent tool calls started, sessionId: {}, turnId: {}, toolCallCount: {}",
                    sessionId, turnId, toolCallRequests.size());
                toolCallEngine.asyncExecute(toolCallRequests, new ToolCallListener() {

                    @Override
                    public void onStart(ToolCallHandle handle) {
                        rememberToolCallHandle(handle);
                    }

                    @Override
                    public void onPartialResult(String id, String partialResult, ToolCallHandle handle) {
                        rememberToolCallHandle(handle);
                        if (cancelled.get()) {
                            return;
                        }
                        synchronized (callbackLock) {
                            if (cancelled.get() || toolExecutionCompleted || isToolCallCompleted(id)) {
                                return;
                            }

                            ToolCallDeltaEvent toolCallDeltaEvent = newEvent(
                                new ToolCallDeltaEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                            toolCallDeltaEvent.setId(id);
                            toolCallDeltaEvent.setPartialResult(partialResult);
                            if (!appendRunningEvent(toolCallDeltaEvent)) {
                                cancelByConflict("append tool call delta failed");
                                return;
                            }
                            latestHeadEventId.set(toolCallDeltaEvent.getEventId());
                            log.debug("agent tool call delta appended, sessionId: {}, turnId: {}, toolCallId: {}, eventId: {}",
                                sessionId, turnId, id, toolCallDeltaEvent.getEventId());
                        }
                    }

                    @Override
                    public void onCompleteResult(String id, String result, ToolCallHandle handle) {
                        rememberToolCallHandle(handle);
                        if (cancelled.get()) {
                            return;
                        }
                        synchronized (callbackLock) {
                            if (cancelled.get() || toolExecutionCompleted || isToolCallCompleted(id)) {
                                return;
                            }

                            ToolCallEndEvent toolCallEndEvent = newEvent(
                                new ToolCallEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                            toolCallEndEvent.setId(id);
                            toolCallEndEvent.setResult(result);
                            toolCallEndEvent.setError(false);
                            if (!appendRunningEvent(toolCallEndEvent)) {
                                cancelByConflict("append tool call end failed");
                                return;
                            }
                            latestHeadEventId.set(toolCallEndEvent.getEventId());
                            markToolCallCompleted(id);
                            log.info("agent tool call completed, sessionId: {}, turnId: {}, toolCallId: {}, eventId: {}",
                                sessionId, turnId, id, toolCallEndEvent.getEventId());
                        }
                    }

                    @Override
                    public void onCompleteAll(ToolCallHandle handle) {
                        rememberToolCallHandle(handle);
                        if (cancelled.get()) {
                            return;
                        }
                        StartTurnResult continuationTurn;
                        synchronized (callbackLock) {
                            if (cancelled.get() || toolExecutionCompleted) {
                                return;
                            }
                            continuationTurn = finishCurrentTurnAndStartContinuationTurn(latestHeadEventId.get());
                            if (!continuationTurn.isStarted()) {
                                cancelByConflict("start continuation turn failed");
                                return;
                            }
                            latestHeadEventId.set(continuationTurn.getHeadEventId());
                            toolExecutionCompleted = true;
                            cancelled.set(true);
                            unregisterThisHandler();
                        }
                        log.info("agent tool calls completed, continue next turn, sessionId: {}, previousTurnId: {}, nextTurnId: {}, headEventId: {}",
                            sessionId, turnId, continuationTurn.getTurnId(), continuationTurn.getHeadEventId());
                        callModel(sessionId, continuationTurn.getTurnId(), continuationTurn.getHeadEventId());
                    }

                    @Override
                    public void onError(Throwable error, ToolCallHandle handle) {
                        rememberToolCallHandle(handle);
                        log.error("agent tool call callback failed, sessionId: {}, turnId: {}", sessionId, turnId, error);
                        failToolExecution(error);
                    }
                });
            } catch (Throwable error) {
                log.error("agent tool call engine failed, sessionId: {}, turnId: {}", sessionId, turnId, error);
                failToolExecution(error);
            }
        }

        private void rememberToolCallHandle(ToolCallHandle handle) {
            if (handle != null) {
                toolCallHandle.compareAndSet(ToolCallHandle.NOOP, handle);
                if (cancelled.get() || toolExecutionCompleted) {
                    cancelToolCallHandle(handle, "callback already cancelled");
                }
            }
        }

        private boolean isToolCallCompleted(String id) {
            return id != null && completedToolCallIds.contains(id);
        }

        private void markToolCallCompleted(String id) {
            if (id != null) {
                completedToolCallIds.add(id);
            }
        }

        private void failToolExecution(Throwable error) {
            synchronized (callbackLock) {
                if (cancelled.get() || toolExecutionCompleted) {
                    return;
                }
                finishCurrentTurnWithErrorLocked(error, true);
                toolExecutionCompleted = true;
                cancelled.set(true);
                cancelActiveWork("tool execution failed");
                unregisterThisHandler();
            }
        }

        private void finishCurrentTurnWithErrorLocked(Throwable error, boolean appendMissingToolResults) {
            if (!appendMessageEndBeforeErrorIfNecessaryLocked()) {
                return;
            }
            if (appendMissingToolResults && !appendMissingToolResultErrorsLocked(error)) {
                return;
            }
            String turnEndEventId = appendErrorAndTurnEnd(sessionId, turnId, latestHeadEventId.get(), error);
            if (turnEndEventId != null) {
                latestHeadEventId.set(turnEndEventId);
            }
        }

        private boolean appendMessageEndBeforeErrorIfNecessaryLocked() {
            if (messageCompleted || !hasAssistantMessageContent()) {
                return true;
            }

            MessageEndEvent messageEndEvent = newEvent(
                new MessageEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
            if (!appendRunningEvent(messageEndEvent)) {
                cancelByConflict("append interrupted message end failed");
                return false;
            }
            latestHeadEventId.set(messageEndEvent.getEventId());
            messageCompleted = true;
            return true;
        }

        private boolean hasAssistantMessageContent() {
            return !textBuilder.isEmpty() || !thinkingBuilder.isEmpty() || !toolCallRequestByIndex.isEmpty();
        }

        private boolean appendMissingToolResultErrorsLocked(Throwable error) {
            String errorMessage = buildErrorMessage(error);
            for (ToolCallRequest toolCallRequest : toolCallRequestByIndex.values()) {
                String toolCallId = toolCallRequest.getId();
                if (isToolCallCompleted(toolCallId)) {
                    continue;
                }

                ToolCallEndEvent toolCallEndEvent = newEvent(
                    new ToolCallEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                toolCallEndEvent.setId(toolCallId);
                toolCallEndEvent.setResult(errorMessage);
                toolCallEndEvent.setError(true);
                if (!appendRunningEvent(toolCallEndEvent)) {
                    cancelByConflict("append missing tool error result failed");
                    return false;
                }
                latestHeadEventId.set(toolCallEndEvent.getEventId());
                markToolCallCompleted(toolCallId);
            }
            return true;
        }

        private void finishTurnAndTryContinuePendingTask() {
            boolean finished;
            synchronized (callbackLock) {
                if (cancelled.get()) {
                    return;
                }

                TurnEndEvent turnEndEvent = newEvent(
                    new TurnEndEvent(), sessionId, turnId, eventRepository.generateEventId(), latestHeadEventId.get());
                finished = appendEventByCas(turnEndEvent, EventSessionStatus.idle, null);
                if (!finished) {
                    cancelByConflict("append turn end failed");
                    return;
                }
                latestHeadEventId.set(turnEndEvent.getEventId());
                log.info("agent turn finished with final answer, sessionId: {}, turnId: {}, eventId: {}",
                    sessionId, turnId, turnEndEvent.getEventId());
            }
            unregisterThisHandler();

            // 只处理运行期间已经 submit 并落库的 pending task；没有 pending task 时会立即返回，不会造成模型循环。
            if (agentRequestTaskRepository.hasPending(sessionId)) {
                log.debug("agent pending task detected after turn end, sessionId: {}, turnId: {}", sessionId, turnId);
                try {
                    tryStartUserTurn(sessionId);
                } catch (RuntimeException error) {
                    // 当前 turn 已正常结束；接力失败只影响 pending task 后续触发，必须打 error 便于上层排查。
                    log.error("agent pending task handoff failed after turn end, sessionId: {}, finishedTurnId: {}",
                        sessionId, turnId, error);
                }
            }
        }

        private StartTurnResult finishCurrentTurnAndStartContinuationTurn(String currentHeadEventId) {
            return transactionExecutor.executeWithRequired(() -> {
                TurnEndEvent turnEndEvent = newEvent(
                    new TurnEndEvent(), sessionId, turnId, eventRepository.generateEventId(), currentHeadEventId);
                boolean turnEnded = eventSessionRepository.casHeadEventId(
                    sessionId, currentHeadEventId, turnEndEvent.getEventId(), EventSessionStatus.busy, turnId);
                if (!turnEnded) {
                    return StartTurnResult.notStarted();
                }
                eventRepository.append(turnEndEvent);

                String nextTurnStartEventId = eventRepository.generateEventId();
                String nextTurnId = nextTurnStartEventId;
                TurnStartEvent nextTurnStartEvent = newEvent(
                    new TurnStartEvent(), sessionId, nextTurnId, nextTurnStartEventId, turnEndEvent.getEventId());
                boolean nextTurnStarted = eventSessionRepository.casHeadEventId(
                    sessionId, turnEndEvent.getEventId(), nextTurnStartEvent.getEventId(), EventSessionStatus.busy, nextTurnId);
                if (!nextTurnStarted) {
                    throw new ConcurrentTurnException("CAS start continuation turn failed, sessionId: " + sessionId);
                }
                eventRepository.append(nextTurnStartEvent);
                return StartTurnResult.started(sessionId, nextTurnId, nextTurnStartEvent.getEventId());
            });
        }

        @Override
        public void onError(Throwable error) {
            log.error("agent streaming callback failed, sessionId: {}, turnId: {}", sessionId, turnId, error);
            failWithLatestHead(error);
        }

        private void failWithLatestHead(Throwable error) {
            synchronized (callbackLock) {
                if (cancelled.get()) {
                    return;
                }
                finishCurrentTurnWithErrorLocked(error, true);
                cancelled.set(true);
                cancelActiveWork("streaming failed");
                unregisterThisHandler();
            }
        }

        private boolean appendRunningEvent(Event event) {
            return appendEventByCas(event, EventSessionStatus.busy, turnId);
        }

        private boolean appendEventByCas(Event event, EventSessionStatus nextStatus, String runningTurnId) {
            return AgentRuntimeEngineImpl.this.appendEventByCas(sessionId, event, nextStatus, runningTurnId);
        }

        private void cancelByConflict(String reason) {
            cancelled.set(true);
            toolExecutionCompleted = true;
            cancelActiveWork(reason);
            unregisterThisHandler();
            log.warn("agent callback chain cancelled by CAS conflict, sessionId: {}, turnId: {}, reason: {}, latestHeadEventId: {}",
                sessionId, turnId, reason, latestHeadEventId.get());
        }

        private void cancelExternal(String reason) {
            synchronized (callbackLock) {
                if (cancelled.get()) {
                    return;
                }
                cancelled.set(true);
                toolExecutionCompleted = true;
                cancelActiveWork(reason);
                unregisterThisHandler();
            }
            log.info("agent callback chain cancelled externally, sessionId: {}, turnId: {}, reason: {}",
                sessionId, turnId, reason);
        }

        private void cancelActiveWork(String reason) {
            cancelStreamingHandle(streamingHandle.get(), reason);
            cancelToolCallHandle(toolCallHandle.get(), reason);
        }

        private void cancelStreamingHandle(StreamingHandle handle, String reason) {
            if (handle == null || handle.isCancelled()) {
                return;
            }
            try {
                handle.cancel();
                log.debug("agent streaming handle cancelled, sessionId: {}, turnId: {}, reason: {}", sessionId, turnId, reason);
            } catch (Throwable error) {
                log.warn("agent streaming handle cancel failed, sessionId: {}, turnId: {}, reason: {}", sessionId, turnId, reason, error);
            }
        }

        private void cancelToolCallHandle(ToolCallHandle handle, String reason) {
            if (handle == null || handle.isCancelled()) {
                return;
            }
            try {
                handle.cancel();
                log.debug("agent tool call handle cancelled, sessionId: {}, turnId: {}, reason: {}", sessionId, turnId, reason);
            } catch (Throwable error) {
                log.warn("agent tool call handle cancel failed, sessionId: {}, turnId: {}, reason: {}", sessionId, turnId, reason, error);
            }
        }

        private void unregisterThisHandler() {
            activeHandlerBySessionId.remove(sessionId, this);
        }
    }

    private String appendErrorAndTurnEnd(String sessionId, String turnId, String parentEventId, Throwable error) {
        return transactionExecutor.executeWithRequired(() -> appendErrorAndTurnEndInCurrentTransaction(
            sessionId, turnId, parentEventId, error));
    }

    private String appendErrorAndTurnEndInCurrentTransaction(String sessionId, String turnId, String parentEventId,
                                                            Throwable error) {
        ErrorEvent errorEvent = newEvent(new ErrorEvent(), sessionId, turnId, eventRepository.generateEventId(), parentEventId);
        errorEvent.setErrorMessage(buildErrorMessage(error));
        boolean errorAppended = eventSessionRepository.casHeadEventId(
            sessionId, parentEventId, errorEvent.getEventId(), EventSessionStatus.busy, turnId);
        if (!errorAppended) {
            log.warn("agent error event append failed by CAS conflict, sessionId: {}, turnId: {}, parentEventId: {}",
                sessionId, turnId, parentEventId, error);
            return null;
        }
        eventRepository.append(errorEvent);

        TurnEndEvent turnEndEvent = newEvent(
            new TurnEndEvent(), sessionId, turnId, eventRepository.generateEventId(), errorEvent.getEventId());
        boolean turnEndAppended = eventSessionRepository.casHeadEventId(
            sessionId, errorEvent.getEventId(), turnEndEvent.getEventId(), EventSessionStatus.idle, null);
        if (!turnEndAppended) {
            throw new ConcurrentTurnException("CAS append error turn_end failed, sessionId: " + sessionId);
        }
        eventRepository.append(turnEndEvent);

        log.error("agent turn failed and closed, sessionId: {}, turnId: {}, errorEventId: {}, turnEndEventId: {}, errorMessage: {}",
            sessionId, turnId, errorEvent.getEventId(), turnEndEvent.getEventId(), errorEvent.getErrorMessage(), error);
        return turnEndEvent.getEventId();
    }

    private String appendAbortAndTurnEndInCurrentTransaction(String sessionId, String turnId, String parentEventId,
                                                            String reason) {
        AbortEvent abortEvent = newEvent(new AbortEvent(), sessionId, turnId, eventRepository.generateEventId(), parentEventId);
        abortEvent.setReason(reason);
        boolean abortAppended = eventSessionRepository.casHeadEventId(
            sessionId, parentEventId, abortEvent.getEventId(), EventSessionStatus.busy, turnId);
        if (!abortAppended) {
            log.warn("agent abort event append failed by CAS conflict, sessionId: {}, turnId: {}, parentEventId: {}",
                sessionId, turnId, parentEventId);
            return null;
        }
        eventRepository.append(abortEvent);

        TurnEndEvent turnEndEvent = newEvent(
            new TurnEndEvent(), sessionId, turnId, eventRepository.generateEventId(), abortEvent.getEventId());
        boolean turnEndAppended = eventSessionRepository.casHeadEventId(
            sessionId, abortEvent.getEventId(), turnEndEvent.getEventId(), EventSessionStatus.idle, null);
        if (!turnEndAppended) {
            throw new ConcurrentTurnException("CAS append abort turn_end failed, sessionId: " + sessionId);
        }
        eventRepository.append(turnEndEvent);

        log.warn("agent turn aborted and closed, sessionId: {}, turnId: {}, abortEventId: {}, turnEndEventId: {}, reason: {}",
            sessionId, turnId, abortEvent.getEventId(), turnEndEvent.getEventId(), reason);
        return turnEndEvent.getEventId();
    }

    private boolean appendEventByCas(String sessionId, Event event, EventSessionStatus nextStatus, String runningTurnId) {
        return transactionExecutor.executeWithRequired(() -> {
            boolean headMoved = eventSessionRepository.casHeadEventId(
                sessionId, event.getParentEventId(), event.getEventId(), nextStatus, runningTurnId);
            if (!headMoved) {
                return false;
            }

            eventRepository.append(event);
            return true;
        });
    }

    private <T extends Event> T newEvent(T event, String sessionId, String turnId, String eventId, String parentEventId) {
        event.setEventId(eventId);
        event.setParentEventId(parentEventId);
        event.setSessionId(sessionId);
        event.setTurnId(turnId);
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private List<ToolCallRequest> convert(List<ToolExecutionRequest> toolExecutionRequests) {
        return NullSafe.of(toolExecutionRequests).stream().map(this::convert).toList();
    }

    private ToolCallRequest convert(ToolExecutionRequest toolExecutionRequest) {
        ToolCallRequest toolCallRequest = new ToolCallRequest();
        toolCallRequest.setId(toolExecutionRequest.id());
        toolCallRequest.setName(toolExecutionRequest.name());
        toolCallRequest.setArguments(toolExecutionRequest.arguments());
        return toolCallRequest;
    }

    private ToolExecutionRequest convert(ToolCallRequest toolCallRequest) {
        return ToolExecutionRequest.builder()
            .id(toolCallRequest.getId())
            .name(toolCallRequest.getName())
            .arguments(toolCallRequest.getArguments())
            .build();
    }

    /**
     * 从事件树重建发给模型的上下文消息。
     * <p>
     * message_end 仅表示 assistant message 边界；模型上下文通过 message_delta/message_tool_call_end 拼接重建。
     * 历史 turn 只有在 turn_end 闭合后才参与重放；当前 busy turn 允许未闭合，用于本轮模型调用输入。
     */
    private List<ChatMessage> buildChatMessageList(List<Event> eventList) {
        if (CollectionUtils.isEmpty(eventList)) {
            return Collections.emptyList();
        }

        List<Event> orderedEvents = new ArrayList<>(eventList);
        Collections.reverse(orderedEvents);

        String currentTurnId = findCurrentTurnId(orderedEvents);
        Set<String> completedTurnIds = findCompletedTurnIds(orderedEvents);
        List<ChatMessage> chatMessages = new ArrayList<>();
        Map<String, String> toolNameById = new HashMap<>();
        AssistantMessageAccumulator assistantMessageAccumulator = new AssistantMessageAccumulator();

        for (Event event : orderedEvents) {
            if (!shouldReplayEvent(event, currentTurnId, completedTurnIds)) {
                continue;
            }

            EventType eventType = event.getEventType();
            if (eventType == EventType.message_start && event instanceof MessageStartEvent messageStartEvent) {
                chatMessages.addAll(NullSafe.of(messageStartEvent.getUserMessageList()).stream()
                    .map(UserMessage::userMessage)
                    .toList());
            } else if (eventType == EventType.message_delta && event instanceof MessageDeltaEvent messageDeltaEvent) {
                assistantMessageAccumulator.appendDelta(messageDeltaEvent);
            } else if (eventType == EventType.message_tool_call_end
                && event instanceof MessageToolCallEndEvent messageToolCallEndEvent) {
                assistantMessageAccumulator.appendToolCall(messageToolCallEndEvent);
            } else if (eventType == EventType.message_end && event instanceof MessageEndEvent messageEndEvent) {
                List<ToolExecutionRequest> toolExecutionRequests = assistantMessageAccumulator.toolExecutionRequests();
                toolExecutionRequests.forEach(request -> {
                    if (request.id() != null) {
                        toolNameById.put(request.id(), request.name());
                    }
                });

                AiMessage.Builder aiMessageBuilder = AiMessage.builder()
                    .toolExecutionRequests(toolExecutionRequests);
                String text = assistantMessageAccumulator.text();
                if (text != null || toolExecutionRequests.isEmpty()) {
                    aiMessageBuilder.text(text == null ? "" : text);
                }
                String thinking = assistantMessageAccumulator.thinking();
                if (thinking != null) {
                    aiMessageBuilder.thinking(thinking);
                }
                if (messageEndEvent.getAttributes() != null) {
                    aiMessageBuilder.attributes(messageEndEvent.getAttributes());
                }
                chatMessages.add(aiMessageBuilder.build());
                assistantMessageAccumulator = new AssistantMessageAccumulator();
            } else if (eventType == EventType.tool_call_start && event instanceof ToolCallStartEvent toolCallStartEvent) {
                toolNameById.put(toolCallStartEvent.getId(), toolCallStartEvent.getName());
            } else if (eventType == EventType.tool_call_end && event instanceof ToolCallEndEvent toolCallEndEvent) {
                String toolName = toolNameById.get(toolCallEndEvent.getId());
                if (toolName == null) {
                    // TODO 如果出现该日志，需要检查工具事件是否被截断或乱序。
                    toolName = toolCallEndEvent.getId();
                    log.warn("tool call name missing when building chat messages, toolCallId: {}, eventId: {}",
                        toolCallEndEvent.getId(), toolCallEndEvent.getEventId());
                }
                chatMessages.add(ToolExecutionResultMessage.builder()
                    .id(toolCallEndEvent.getId())
                    .toolName(toolName)
                    .text(toolCallEndEvent.getResult() == null ? "" : toolCallEndEvent.getResult())
                    .isError(toolCallEndEvent.isError())
                    .build());
            }
        }

        return chatMessages;
    }

    private String findCurrentTurnId(List<Event> orderedEvents) {
        for (int i = orderedEvents.size() - 1; i >= 0; i--) {
            String turnId = orderedEvents.get(i).getTurnId();
            if (turnId != null) {
                return turnId;
            }
        }
        return null;
    }

    private Set<String> findCompletedTurnIds(List<Event> orderedEvents) {
        Set<String> completedTurnIds = new HashSet<>();
        for (Event event : orderedEvents) {
            if (event.getEventType() == EventType.turn_end && event.getTurnId() != null) {
                completedTurnIds.add(event.getTurnId());
            }
        }
        return completedTurnIds;
    }

    private boolean shouldReplayEvent(Event event, String currentTurnId, Set<String> completedTurnIds) {
        String turnId = event.getTurnId();
        if (turnId == null) {
            // 兼容早期骨架写入的无 turnId 事件；新事件必须写入 turnId，便于按 turn 过滤未完成上下文。
            // TODO 存量数据迁移后可收紧为直接拒绝无 turnId 的事件。
            return true;
        }
        return Objects.equals(turnId, currentTurnId) || completedTurnIds.contains(turnId);
    }

    private List<Event> backtrace(String eventId) {
        if (eventId == null) {
            return Collections.emptyList();
        }

        List<Event> eventList = new ArrayList<>();
        Event curEvent = eventRepository.get(eventId);
        while (curEvent != null) {
            eventList.add(curEvent);
            String parentEventId = curEvent.getParentEventId();
            curEvent = parentEventId == null ? null : eventRepository.get(parentEventId);
        }
        return eventList;
    }

    private String buildErrorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        if (isNotBlank(message)) {
            return error.getClass().getName() + ": " + message;
        }
        return error.getClass().getName();
    }

    private boolean isNotBlank(String str) {
        return str != null && !str.isBlank();
    }

    private final class AssistantMessageAccumulator {

        private final StringBuilder textBuilder = new StringBuilder();
        private final StringBuilder thinkingBuilder = new StringBuilder();
        private final TreeMap<Integer, MutableToolCall> toolCallByIndex = new TreeMap<>();

        void appendDelta(MessageDeltaEvent messageDeltaEvent) {
            if (messageDeltaEvent.getType() == MessageDeltaType.text) {
                if (messageDeltaEvent.isReplace()) {
                    textBuilder.setLength(0);
                }
                appendIfNotNull(textBuilder, messageDeltaEvent.getPartialText());
            } else if (messageDeltaEvent.getType() == MessageDeltaType.thinking) {
                if (messageDeltaEvent.isReplace()) {
                    thinkingBuilder.setLength(0);
                }
                appendIfNotNull(thinkingBuilder, messageDeltaEvent.getPartialText());
            } else if (messageDeltaEvent.getType() == MessageDeltaType.tool_call) {
                Integer index = messageDeltaEvent.getIndex();
                if (index == null) {
                    return;
                }
                toolCallByIndex.computeIfAbsent(index, ignored -> new MutableToolCall())
                    .appendDelta(messageDeltaEvent);
            }
        }

        void appendToolCall(MessageToolCallEndEvent messageToolCallEndEvent) {
            Integer index = messageToolCallEndEvent.getIndex();
            if (index == null) {
                return;
            }
            toolCallByIndex.computeIfAbsent(index, ignored -> new MutableToolCall())
                .appendComplete(messageToolCallEndEvent);
        }

        String text() {
            return textBuilder.isEmpty() ? null : textBuilder.toString();
        }

        String thinking() {
            return thinkingBuilder.isEmpty() ? null : thinkingBuilder.toString();
        }

        List<ToolExecutionRequest> toolExecutionRequests() {
            return toolCallByIndex.values().stream()
                .map(MutableToolCall::toToolCallRequest)
                .map(AgentRuntimeEngineImpl.this::convert)
                .toList();
        }

        private void appendIfNotNull(StringBuilder builder, String str) {
            if (str != null) {
                builder.append(str);
            }
        }
    }

    private static final class MutableToolCall {

        private String id;
        private String name;
        private final StringBuilder argumentsBuilder = new StringBuilder();

        void appendDelta(MessageDeltaEvent messageDeltaEvent) {
            if (messageDeltaEvent.getId() != null) {
                this.id = messageDeltaEvent.getId();
            }
            if (messageDeltaEvent.getName() != null) {
                this.name = messageDeltaEvent.getName();
            }
            if (messageDeltaEvent.getPartialArguments() != null) {
                this.argumentsBuilder.append(messageDeltaEvent.getPartialArguments());
            }
        }

        void appendComplete(MessageToolCallEndEvent messageToolCallEndEvent) {
            this.id = messageToolCallEndEvent.getId();
            this.name = messageToolCallEndEvent.getName();
            this.argumentsBuilder.setLength(0);
            if (messageToolCallEndEvent.getArguments() != null) {
                this.argumentsBuilder.append(messageToolCallEndEvent.getArguments());
            }
        }

        ToolCallRequest toToolCallRequest() {
            ToolCallRequest toolCallRequest = new ToolCallRequest();
            toolCallRequest.setId(id);
            toolCallRequest.setName(name);
            toolCallRequest.setArguments(argumentsBuilder.toString());
            return toolCallRequest;
        }
    }

    private static final class StartTurnResult {

        private static final StartTurnResult NOT_STARTED = new StartTurnResult(false, null, null, null);

        private final boolean started;
        private final String sessionId;
        private final String turnId;
        private final String headEventId;

        private StartTurnResult(boolean started, String sessionId, String turnId, String headEventId) {
            this.started = started;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.headEventId = headEventId;
        }

        static StartTurnResult started(String sessionId, String turnId, String headEventId) {
            return new StartTurnResult(true, sessionId, turnId, headEventId);
        }

        static StartTurnResult notStarted() {
            return NOT_STARTED;
        }

        boolean isStarted() {
            return started;
        }

        String getSessionId() {
            return sessionId;
        }

        String getTurnId() {
            return turnId;
        }

        String getHeadEventId() {
            return headEventId;
        }
    }

    private static class ConcurrentTurnException extends RuntimeException {

        private ConcurrentTurnException(String message) {
            super(message);
        }
    }

}
