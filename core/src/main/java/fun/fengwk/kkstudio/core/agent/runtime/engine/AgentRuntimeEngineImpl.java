package fun.fengwk.kkstudio.core.agent.runtime.engine;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import fun.fengwk.convention4j.common.util.CollectionUtils;
import fun.fengwk.convention4j.common.util.NullSafe;
import fun.fengwk.convention4j.springboot.starter.transaction.TransactionExecutor;
import fun.fengwk.kkstudio.core.agent.runtime.event.*;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.*;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.Provider;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderManager;
import fun.fengwk.kkstudio.core.agent.runtime.queue.AgentRequestQueue;
import fun.fengwk.kkstudio.core.agent.runtime.repo.AgentRequestTaskRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventSessionRepository;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallEngine;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallListener;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author fengwk
 */
@Slf4j
@Component
public class AgentRuntimeEngineImpl implements AgentRuntimeEngine {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final EventSessionRepository eventSessionRepository;
    private final EventRepository eventRepository;
    private final AgentRequestTaskRepository agentRequestTaskRepository;
    private final TransactionExecutor transactionExecutor;
    private final ToolCallEngine toolCallEngine;

    private final ProviderManager providerManager;
//    private final AgentRequestQueue requestQueue;

//    private volatile Status status;
//    private final Thread worker;

    public AgentRuntimeEngineImpl(EventManager eventManager, EventSessionStore eventSessionStore, EventSessionRepository eventSessionRepository, EventStore eventStore, EventRepository eventRepository, AgentRequestTaskRepository agentRequestTaskRepository, TransactionExecutor transactionExecutor,
                                  ProviderManager providerManager,
                                  AgentRequestQueue requestQueue, ToolCallEngine toolCallEngine) {
        this.eventSessionRepository = eventSessionRepository;
        this.eventRepository = eventRepository;
        this.agentRequestTaskRepository = agentRequestTaskRepository;
        this.transactionExecutor = transactionExecutor;
        this.providerManager = Objects.requireNonNull(providerManager, "providerManager must not be null");
//        this.eventManager = Objects.requireNonNull(eventManager, "eventManager must not be null");
//        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue must not be null");
//        this.status = Status.init;
//        this.worker = new Thread(this, "agent-runtime-engine-" + SEQUENCE.incrementAndGet());
        this.toolCallEngine = toolCallEngine;
    }

//    @PostConstruct
//    public void initialize() {
//        synchronized (this) {
//            if (status != Status.init && status != Status.stopped) {
//                return;
//            }
//
//            this.status = Status.started;
//            if (status == Status.init) {
//                worker.start();
//            }
//            notifyAll();
//        }
//    }
//
//    @PreDestroy
//    public void destroy() {
//        synchronized (this) {
//            if (this.status == Status.closed) {
//                return;
//            }
//
//            this.status = Status.closed;
//            notifyAll();
//        }
//    }

    @Override
    public String newSession() {
        EventSession eventSession = eventSessionRepository.newSession(null);
        return eventSession.getSessionId();
    }

    @Override
    public String fork(String headEventId) {
        EventSession eventSession = eventSessionRepository.newSession(headEventId);
        return eventSession.getSessionId();
    }

    @Override
    public void submit(AgentRequest agentRequest) {
        AgentRequestTask agentRequestTask = new AgentRequestTask();
        agentRequestTask.setTaskId(agentRequestTaskRepository.generateTaskId());
        agentRequestTask.setSessionId(agentRequest.getSessionId());
        agentRequestTask.setProviderConfig(agentRequest.getProviderConfig());
        agentRequestTask.setModelRequestConfig(agentRequest.getModelRequestConfig());
        agentRequestTask.setParameters(agentRequest.getParameters());
        agentRequestTask.setSystemPrompt(agentRequest.getSystemPrompt());
        agentRequestTask.setUserMessage(agentRequest.getUserMessage());
        agentRequestTask.setConsume(false);
        agentRequestTaskRepository.add(agentRequestTask);

        String sessionId = agentRequest.getSessionId();
        boolean startTurn = tryStartTurn(sessionId);
        if (!startTurn) {
            return;
        }

        execTurn(sessionId, 0);
    }

    private void execTurn(String sessionId, int failedCount) {
        EventSession eventSession = eventSessionRepository.get(sessionId);
        if (eventSession == null) {
            // TODO log.error 未知错误
            return;
        }

        List<AgentRequestTask> agentRequestTaskList = agentRequestTaskRepository.listBySessionId(sessionId);
        if (CollectionUtils.isNotEmpty(agentRequestTaskList)) {
            // 以最新的提交为基准
            AgentRequestTask agentRequestTask = agentRequestTaskList.get(agentRequestTaskList.size() - 1);
            ProviderConfig providerConfig = agentRequestTask.getProviderConfig();
            ModelRequestConfig modelRequestConfig = agentRequestTask.getModelRequestConfig();
            Map<String, Object> parameters = agentRequestTask.getParameters();
            String systemPrompt = agentRequestTask.getSystemPrompt();

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
                eventSessionRepository.updateSessionConfig(
                    sessionId, providerConfig, modelRequestConfig, parameters, systemPrompt);
            }
        }

        // 构造消息
        String headEventId = eventSession.getHeadEventId();
        List<Event> eventList = backtrace(headEventId);
        List<ChatMessage> hisMessageList = buildChatMessageList(eventList);
        List<ChatMessage> messageList = new ArrayList<>();
        messageList.add(SystemMessage.systemMessage(eventSession.getSystemPrompt()));
        messageList.addAll(hisMessageList);
        // 最新的用户消息
        List<UserMessage> userMessageList = new ArrayList<>();
        for (AgentRequestTask agentRequestTask : agentRequestTaskList) {
            UserMessage userMessage = UserMessage.userMessage(agentRequestTask.getUserMessage());
            userMessageList.add(userMessage);
            messageList.add(userMessage);
        }

        // 追加用户消息到 event 中

        MessageStartEvent messageStartEvent = new MessageStartEvent();
        messageStartEvent.setEventId(eventRepository.generateEventId());
        messageStartEvent.setParentEventId(headEventId);
        messageStartEvent.setCreateTime(LocalDateTime.now());
        messageStartEvent.setMessageList(userMessageList);
        if (!casAndAppendEvent(sessionId, messageStartEvent)) {
            // TODO log.error 意外的冲突
            return;
        }

        // 执行请求
        Provider provider = providerManager.getProvider(eventSession.getProviderConfig());
        ChatRequest chatRequest = provider.buildChatRequest(messageList, eventSession.getModelRequestConfig());
        StreamingChatModel chatModel = provider.getChatModel();

        AtomicReference<String> latestHeadEventId = new AtomicReference<>(messageStartEvent.getEventId());
        Object lock = new Object();

        chatModel.chat(chatRequest, new StreamingChatResponseHandler() {

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                // TODO log.debug 日志
                String eventId = eventRepository.generateEventId();
                synchronized (lock) {
                    MessageDeltaEvent messageDeltaEvent = new MessageDeltaEvent();
                    messageDeltaEvent.setEventId(eventId);
                    messageDeltaEvent.setParentEventId(latestHeadEventId.get());
                    messageDeltaEvent.setCreateTime(LocalDateTime.now());
                    messageDeltaEvent.setType(MessageDeltaType.thinking);
                    messageDeltaEvent.setPartialText(partialThinking.text());
                    if (!casAndAppendEvent(sessionId, messageDeltaEvent)) {
                        // TODO log.warn 取消或未预料的异常
                        return;
                    }
                    latestHeadEventId.set(eventId);
                }
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                // TODO log.debug 日志
                String eventId = eventRepository.generateEventId();
                synchronized (lock) {
                    MessageDeltaEvent messageDeltaEvent = new MessageDeltaEvent();
                    messageDeltaEvent.setEventId(eventId);
                    messageDeltaEvent.setParentEventId(latestHeadEventId.get());
                    messageDeltaEvent.setCreateTime(LocalDateTime.now());
                    messageDeltaEvent.setType(MessageDeltaType.text);
                    messageDeltaEvent.setPartialText(partialResponse.text());
                    if (!casAndAppendEvent(sessionId, messageDeltaEvent)) {
                        // TODO log.warn 取消或未预料的异常
                        return;
                    }
                    latestHeadEventId.set(eventId);
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                // TODO log.debug 日志
                String eventId = eventRepository.generateEventId();
                synchronized (lock) {
                    MessageDeltaEvent messageDeltaEvent = new MessageDeltaEvent();
                    messageDeltaEvent.setEventId(eventId);
                    messageDeltaEvent.setParentEventId(latestHeadEventId.get());
                    messageDeltaEvent.setCreateTime(LocalDateTime.now());
                    messageDeltaEvent.setType(MessageDeltaType.tool_call);
                    messageDeltaEvent.setIndex(partialToolCall.index());
                    messageDeltaEvent.setId(partialToolCall.id());
                    messageDeltaEvent.setName(partialToolCall.name());
                    messageDeltaEvent.setPartialArguments(partialToolCall.partialArguments());
                    if (!casAndAppendEvent(sessionId, messageDeltaEvent)) {
                        // TODO log.warn 取消或未预料的异常
                        return;
                    }
                    latestHeadEventId.set(eventId);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // TODO log.debug 日志
                AiMessage aiMessage = completeResponse.aiMessage();
                List<ToolCallRequest> toolCallRequests = convert(aiMessage.toolExecutionRequests());
                String eventId = eventRepository.generateEventId();
                synchronized (lock) {
                    MessageEndEvent messageEndEvent = new MessageEndEvent();
                    messageEndEvent.setEventId(eventId);
                    messageEndEvent.setParentEventId(latestHeadEventId.get());
                    messageEndEvent.setCreateTime(LocalDateTime.now());
                    messageEndEvent.setText(aiMessage.text());
                    messageEndEvent.setThinking(aiMessage.thinking());
                    messageEndEvent.setToolCallRequests(toolCallRequests);
                    messageEndEvent.setAttributes(aiMessage.attributes());
                    if (!casAndAppendEvent(sessionId, messageEndEvent)) {
                        // TODO log.warn 取消或未预料的异常
                        return;
                    }
                    latestHeadEventId.set(eventId);
                }

                // TODO 如果是工具调用还需要完成工具调用流程

                if (CollectionUtils.isNotEmpty(toolCallRequests)) {
                    synchronized (lock) {
                        for (ToolCallRequest toolCallRequest : toolCallRequests) {
                            String toolCallStartEventId = eventRepository.generateEventId();
                            ToolCallStartEvent toolCallStartEvent = new ToolCallStartEvent();
                            toolCallStartEvent.setEventId(toolCallStartEventId);
                            toolCallStartEvent.setParentEventId(latestHeadEventId.get());
                            toolCallStartEvent.setCreateTime(LocalDateTime.now());
                            toolCallStartEvent.setId(toolCallRequest.getId());
                            toolCallStartEvent.setName(toolCallRequest.getName());
                            toolCallStartEvent.setArguments(toolCallRequest.getArguments());
                            if (!casAndAppendEvent(sessionId, toolCallStartEvent)) {
                                // TODO log.warn 取消或未预料的异常
                                return;
                            }
                            latestHeadEventId.set(toolCallStartEventId);
                        }
                    }

                    toolCallEngine.asyncExecute(toolCallRequests, new ToolCallListener() {

                        @Override
                        public void onPartialResult(String id, String partialResult) {
                            synchronized (lock) {
                                String toolCallDeltaEventId = eventRepository.generateEventId();
                                ToolCallDeltaEvent toolCallDeltaEvent = new ToolCallDeltaEvent();
                                toolCallDeltaEvent.setEventId(toolCallDeltaEventId);
                                toolCallDeltaEvent.setParentEventId(latestHeadEventId.get());
                                toolCallDeltaEvent.setCreateTime(LocalDateTime.now());
                                toolCallDeltaEvent.setId(id);
                                toolCallDeltaEvent.setPartialResult(partialResult);
                                if (!casAndAppendEvent(sessionId, toolCallDeltaEvent)) {
                                    // TODO log.warn 取消或未预料的异常
                                    return;
                                }
                                latestHeadEventId.set(toolCallDeltaEventId);
                            }
                        }

                        @Override
                        public void onCompleteResult(String id, String result) {
                            synchronized (lock) {
                                String toolCallEndEventId = eventRepository.generateEventId();
                                ToolCallEndEvent toolCallEndEvent = new ToolCallEndEvent();
                                toolCallEndEvent.setEventId(toolCallEndEventId);
                                toolCallEndEvent.setParentEventId(latestHeadEventId.get());
                                toolCallEndEvent.setCreateTime(LocalDateTime.now());
                                toolCallEndEvent.setId(id);
                                toolCallEndEvent.setResult(result);
                                if (!casAndAppendEvent(sessionId, toolCallEndEvent)) {
                                    // TODO log.warn 取消或未预料的异常
                                    return;
                                }
                                latestHeadEventId.set(toolCallEndEventId);
                            }
                        }

                        @Override
                        public void onCompleteAll() {
                            synchronized (lock) {
                                String turnEndEventId = eventRepository.generateEventId();
                                TurnEndEvent turnEndEvent = new TurnEndEvent();
                                turnEndEvent.setEventId(turnEndEventId);
                                turnEndEvent.setParentEventId(latestHeadEventId.get());
                                turnEndEvent.setCreateTime(LocalDateTime.now());
                                if (!casAndAppendEvent(sessionId, turnEndEvent)) {
                                    // TODO log.warn 取消或未预料的异常
                                    return;
                                }
                                latestHeadEventId.set(turnEndEventId);
                            }

                            // 继续下一轮调用，直到没有 tool call 不会进入此流程
                            execTurn(sessionId, 0);
                        }

                    });
                }
            }

            @Override
            public void onError(Throwable error) {
                // TODO log.error 日志
                String eventId = eventRepository.generateEventId();
                synchronized (lock) {
                    ErrorEvent errorEvent = new ErrorEvent();
                    errorEvent.setEventId(eventId);
                    errorEvent.setParentEventId(latestHeadEventId.get());
                    errorEvent.setCreateTime(LocalDateTime.now());
                    errorEvent.setErrorMessage(error.getMessage());
                    if (!casAndAppendEvent(sessionId, errorEvent)) {
                        // TODO log.warn 取消或未预料的异常
                        return;
                    }
                    latestHeadEventId.set(eventId);
                }

                // TODO 失败重试？ 是否支持从任意 event 重试？
//                if (failedCount < 3) {
//                    execTurn(sessionId, failedCount + 1);
//                }
            }

        });
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

    private List<ChatMessage> buildChatMessageList(List<Event> eventList) {
        // TODO 根据事件反推消息列表
        return Collections.emptyList();
    }

    private List<Event> backtrace(String eventId) {
        List<Event> eventList = new ArrayList<>();
        // TODO kv 缓存优化
        Event curEvent = eventRepository.get(eventId);
        while (curEvent != null) {
            eventList.add(curEvent);
            String parentEventId = curEvent.getParentEventId();
            if (parentEventId == null) {
                curEvent = null;
            } else {
                curEvent = eventRepository.get(parentEventId);
            }
        }
        return eventList;
    }

    public boolean tryStartTurn(String sessionId) {
        EventSession eventSession = eventSessionRepository.get(sessionId);
        if (eventSession == null) {
            // TODO log.warn 错误的 sessionId
            return false;
        }

        Event headEvent = eventRepository.get(eventSession.getHeadEventId());
        if (headEvent == null) {
            // TODO log.error 脏数据
            return false;
        }

        if (eventSession.getStatus() != EventSessionStatus.idle && headEvent.getEventType() != EventType.turn_end) {
            // TODO log.debug 当前还在运行中不能插入新用户提交，先排队后续其它运行结束后会继续
            return false;
        }

        TurnStartEvent turnStartEvent = new TurnStartEvent();
        turnStartEvent.setEventId(eventRepository.generateEventId());
        turnStartEvent.setParentEventId(eventSession.getHeadEventId());
        turnStartEvent.setCreateTime(LocalDateTime.now());

        return casAndAppendEvent(eventSession.getSessionId(), turnStartEvent);
    }

//    private boolean casAndAppendEvent(String sessionId, Event event) {
//        EventSession eventSession = eventSessionRepository.get(sessionId);
//        return casAndAppendEvent(eventSession, event);
//    }

    private boolean casAndAppendEvent(String sessionId, Event event) {
        // 事务性进行 cas 并写入 event 避免出现脏数据
        return transactionExecutor.executeWithRequired(() -> {
            // TODO 要测试首次构建 session headEventId 为 null 的情况 cas 是否有效
            boolean lockHead = eventSessionRepository.casHeadEventIdWithBusy(sessionId, event.getParentEventId(), event.getEventId());
            if (!lockHead) {
                // TODO log.info 并发冲突，有其它线程抢占的当前 turn
                return false;
            }

            eventRepository.append(event);
            // TODO log.debug
            return true;
        });
    }

//    private void tryExecUserSubmit(String sessionId) {
//        EventSessionStore eventSessionStore = eventManager.getEventSessionStore();
//        EventSession eventSession = eventSessionStore.get(sessionId);
//        if (eventSession == null) {
//            // TODO log.warn params error clean it
//            requestQueue.takeAll(sessionId);
//            return;
//        }
//
//        EventStore eventStore = eventManager.getEventStore();
//        Event headEvent = eventStore.get(eventSession.getHeadEventId());
//        // event session 空闲或者运行到一个 turn 结束时才能插入用户提交内容
//        if (eventSession.getStatus() != EventSessionStatus.idle
//            && headEvent.getEventType() != EventType.turn_end && headEvent.getEventType() != EventType.agent_end) {
//            // TODO log.debug
//            return;
//        }
//
//
//        List<AgentRequest> agentRequestList = requestQueue.takeAll(sessionId);
//        if (CollectionUtils.isEmpty(agentRequestList)) {
//            // TODO log.debug
//            return;
//        }
//
//        // 以最新的提交为基准
//        AgentRequest agentRequest = agentRequestList.get(agentRequestList.size() - 1);
//
//
//
//
//
//    }
//
//    @Override
//    public void run() {
//        while (status != Status.closed) {
//            if (status == Status.started) {
//                try {
//
//
//
//                    EventStore eventStore = eventManager.getEventStore();
//
////                    eventSessionStore.get()
//
////                    eventStore.get()
//
//                    // 获取消息队列中待处理的请求
//                    List<AgentRequest> agentRequestList = requestQueue.takeAll();
//                    if (CollectionUtils.isEmpty(agentRequestList)) {
//                        // 正常路径不会出现 empty
//                        log.error("request queue take empty request list");
//                        continue;
//                    }
//
//                    // 以最新的提交为基准
//                    AgentRequest agentRequest = agentRequestList.get(agentRequestList.size() - 1);
//                    EventSessionStore eventSessionStore = eventManager.getEventSessionStore();
//                    eventSessionStore.get(agentRequest.getSessionId());
//
//                    ProviderConfig providerConfig = agentRequest.getProviderConfig();
//                    ModelRequestConfig modelRequestConfig = agentRequest.getModelRequestConfig();
//                    Map<String, Object> parameters = agentRequest.getParameters();
//                    String systemPrompt = agentRequest.getSystemPrompt();
//
//                    // 追加用户提交事件
//                    List<String> userMessageList = agentRequestList.stream().map(AgentRequest::getUserMessage).toList();
//                    UserSubmitEvent userSubmitEvent = UserSubmitEvent.builder()
//                        .providerType(providerConfig.getProviderType())
//                        .modelRequestConfig(modelRequestConfig)
//                        .parameters(parameters)
//                        .systemPrompt(systemPrompt)
//                        .userMessageList(userMessageList)
//                        .build();
//                    eventManager.getEventStore().append(userSubmitEvent);
//
//                    // 启动一轮新的 agent
//                    Provider provider = providerManager.getProvider(providerConfig);
//                    List<ChatMessage> hisMessageList = eventManager.buildMessageList(request.getHeadEventId());
//                    List<ChatMessage> messageList = new ArrayList<>();
//                    messageList.add(SystemMessage.systemMessage(request.getSystemPrompt()));
//                    messageList.addAll(hisMessageList);
//                    hisMessageList.add()
//                    ChatRequest chatRequest = provider.buildChatRequest(messageList, request.getModelRequestConfig());
//                    StreamingChatModel chatModel = provider.getChatModel();
//                    chatModel.chat(chatRequest, new TurnExecutor(eventManager.getEventStore()));
//                } catch (InterruptedException ignore) {
//                    Thread.currentThread().interrupt();
//                }
//            } else {
//                synchronized (this) {
//                    try {
//                        wait();
//                    } catch (InterruptedException ignore) {
//                        Thread.currentThread().interrupt();
//                    }
//                }
//            }
//        }
//    }
//
//    public boolean start() {
//        synchronized (this) {
//            if (status != Status.init && status != Status.stopped) {
//                return false;
//            }
//
//            this.status = Status.started;
//            if (status == Status.init) {
//                worker.start();
//            }
//            notifyAll();
//            return true;
//        }
//    }
//
//    public boolean stop() {
//        synchronized (this) {
//            if (status != Status.started) {
//                return false;
//            }
//
//            this.status = Status.stopped;
//            return true;
//        }
//    }
//
//    @Override
//    public void close() {
//        synchronized (this) {
//            if (this.status == Status.closed) {
//                return;
//            }
//
//            this.status = Status.closed;
//            notifyAll();
//        }
//    }
//
//    enum Status {
//
//        init, started, stopped, closed;
//
//    }

}
