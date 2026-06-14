package fun.fengwk.kkstudio.core.agent.runtime.engine;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import fun.fengwk.convention4j.springboot.starter.transaction.TransactionExecutor;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStatus;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.Event;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.EventType;
import fun.fengwk.kkstudio.core.agent.runtime.event.ev.ToolCallEndEvent;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.Provider;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderManager;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.recovery.AgentBusyRecoveryChecker;
import fun.fengwk.kkstudio.core.agent.runtime.repo.AgentRequestTaskRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventRepository;
import fun.fengwk.kkstudio.core.agent.runtime.repo.EventSessionRepository;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallEngine;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallHandle;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallListener;
import fun.fengwk.kkstudio.core.agent.runtime.tool.ToolCallRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRuntimeEngineImpl} 单元测试。
 *
 * @author fengwk
 */
public class AgentRuntimeEngineImplTest {

    @Test
    public void testFinalAnswerClosesTurn() {
        InMemoryEventSessionRepository sessionRepository = new InMemoryEventSessionRepository();
        InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository();
        AgentRuntimeEngineImpl engine = newEngine(
            sessionRepository, eventRepository, taskRepository, new FinalAnswerModel(), new NoopToolCallEngine());

        String sessionId = engine.newSession();
        engine.submit(agentRequest(sessionId, "hello"));

        List<EventType> eventTypes = eventRepository.events.stream()
            .map(Event::getEventType)
            .toList();
        assertEquals(List.of(
            EventType.turn_start,
            EventType.message_start,
            EventType.message_delta,
            EventType.message_end,
            EventType.turn_end), eventTypes);
        assertEquals(EventSessionStatus.idle, sessionRepository.session.getStatus());
    }

    @Test
    public void testToolCallContinuesNextTurnWithToolResult() {
        InMemoryEventSessionRepository sessionRepository = new InMemoryEventSessionRepository();
        InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository();
        ToolThenFinalModel model = new ToolThenFinalModel();
        AgentRuntimeEngineImpl engine = newEngine(
            sessionRepository, eventRepository, taskRepository, model, new SuccessToolCallEngine());

        String sessionId = engine.newSession();
        engine.submit(agentRequest(sessionId, "read"));

        List<EventType> eventTypes = eventRepository.events.stream()
            .map(Event::getEventType)
            .toList();
        assertEquals(List.of(
            EventType.turn_start,
            EventType.message_start,
            EventType.message_tool_call_end,
            EventType.message_end,
            EventType.tool_call_start,
            EventType.tool_call_end,
            EventType.turn_end,
            EventType.turn_start,
            EventType.message_delta,
            EventType.message_end,
            EventType.turn_end), eventTypes);
        assertEquals(2, model.callCount.get());
        assertEquals(EventSessionStatus.idle, sessionRepository.session.getStatus());
    }

    @Test
    public void testStreamingErrorAfterToolCallClosesToolResult() {
        InMemoryEventSessionRepository sessionRepository = new InMemoryEventSessionRepository();
        InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository();
        AgentRuntimeEngineImpl engine = newEngine(
            sessionRepository,
            eventRepository,
            taskRepository,
            new ErrorAfterToolCallModel(),
            new NoopToolCallEngine());

        String sessionId = engine.newSession();
        engine.submit(agentRequest(sessionId, "hello"));

        List<EventType> eventTypes = eventRepository.events.stream()
            .map(Event::getEventType)
            .toList();
        assertEquals(List.of(
            EventType.turn_start,
            EventType.message_start,
            EventType.message_tool_call_end,
            EventType.message_end,
            EventType.tool_call_end,
            EventType.error,
            EventType.turn_end), eventTypes);

        ToolCallEndEvent toolCallEndEvent = (ToolCallEndEvent) eventRepository.events.get(4);
        assertEquals("call_1", toolCallEndEvent.getId());
        assertTrue(toolCallEndEvent.isError());
        assertEquals(EventSessionStatus.idle, sessionRepository.session.getStatus());
    }

    @Test
    public void testAbortClosesPartialAssistantMessage() {
        InMemoryEventSessionRepository sessionRepository = new InMemoryEventSessionRepository();
        InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        InMemoryAgentRequestTaskRepository taskRepository = new InMemoryAgentRequestTaskRepository();
        AgentRuntimeEngineImpl engine = newEngine(
            sessionRepository, eventRepository, taskRepository, new HangingPartialModel(), new NoopToolCallEngine());

        String sessionId = engine.newSession();
        engine.submit(agentRequest(sessionId, "hello"));

        assertEquals(EventSessionStatus.busy, sessionRepository.session.getStatus());
        assertTrue(engine.abort(sessionId, "user abort"));

        List<EventType> eventTypes = eventRepository.events.stream()
            .map(Event::getEventType)
            .toList();
        assertEquals(List.of(
            EventType.turn_start,
            EventType.message_start,
            EventType.message_delta,
            EventType.message_end,
            EventType.abort,
            EventType.turn_end), eventTypes);
        assertEquals(EventSessionStatus.idle, sessionRepository.session.getStatus());
    }

    private AgentRequest agentRequest(String sessionId, String userMessage) {
        return AgentRequest.builder()
            .sessionId(sessionId)
            .providerConfig(ProviderConfig.builder()
                .providerType(ProviderType.openai)
                .apiKey("test")
                .build())
            .modelRequestConfig(ModelRequestConfig.builder()
                .modelName("test-model")
                .build())
            .userMessage(userMessage)
            .build();
    }

    private AgentRuntimeEngineImpl newEngine(InMemoryEventSessionRepository sessionRepository,
                                             InMemoryEventRepository eventRepository,
                                             InMemoryAgentRequestTaskRepository taskRepository,
                                             StreamingChatModel chatModel,
                                             ToolCallEngine toolCallEngine) {
        return new AgentRuntimeEngineImpl(
            sessionRepository,
            eventRepository,
            taskRepository,
            new TransactionExecutor(),
            providerManager(chatModel),
            toolCallEngine,
            emptyObjectProvider());
    }

    private ProviderManager providerManager(StreamingChatModel chatModel) {
        return providerConfig -> new Provider() {

            @Override
            public ChatRequest buildChatRequest(List<ChatMessage> messageList, ModelRequestConfig modelConfig) {
                return ChatRequest.builder()
                    .messages(messageList)
                    .modelName(modelConfig.getModelName())
                    .build();
            }

            @Override
            public StreamingChatModel getChatModel() {
                return chatModel;
            }

            @Override
            public ProviderType getProviderType() {
                return providerConfig.getProviderType();
            }
        };
    }

    private ObjectProvider<AgentBusyRecoveryChecker> emptyObjectProvider() {
        return new ObjectProvider<>() {

            @Override
            public AgentBusyRecoveryChecker getObject(Object... args) {
                return null;
            }

            @Override
            public AgentBusyRecoveryChecker getIfAvailable() {
                return null;
            }

            @Override
            public AgentBusyRecoveryChecker getObject() {
                return null;
            }
        };
    }

    private static class ErrorAfterToolCallModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                .id("call_1")
                .name("read_file")
                .arguments("{}")
                .build()));
            handler.onError(new IllegalStateException("stream failed"));
        }
    }

    private static class HangingPartialModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse(new PartialResponse("partial"), null);
        }
    }

    private static class FinalAnswerModel implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage("final answer"))
                .build());
        }
    }

    private static class ToolThenFinalModel implements StreamingChatModel {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            int currentCall = callCount.incrementAndGet();
            if (currentCall == 1) {
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("call_1")
                    .name("read_file")
                    .arguments("{}")
                    .build();
                handler.onCompleteToolCall(new CompleteToolCall(0, request));
                handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(List.of(request)))
                    .build());
            } else {
                ToolExecutionResultMessage toolResultMessage = assertInstanceOf(
                    ToolExecutionResultMessage.class, chatRequest.messages().get(chatRequest.messages().size() - 1));
                assertEquals("file content", toolResultMessage.text());
                handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage("done"))
                    .build());
            }
        }
    }

    private static class NoopToolCallEngine implements ToolCallEngine {

        @Override
        public void asyncExecute(List<ToolCallRequest> toolCallRequests, ToolCallListener toolCallListener) {
            throw new AssertionError("tool engine should not be invoked when stream fails before complete response");
        }
    }

    private static class SuccessToolCallEngine implements ToolCallEngine {

        @Override
        public void asyncExecute(List<ToolCallRequest> toolCallRequests, ToolCallListener toolCallListener) {
            ToolCallHandle handle = ToolCallHandle.NOOP;
            toolCallListener.onStart(handle);
            for (ToolCallRequest toolCallRequest : toolCallRequests) {
                toolCallListener.onCompleteResult(toolCallRequest.getId(), "file content", handle);
            }
            toolCallListener.onCompleteAll(handle);
        }
    }

    private static class InMemoryEventSessionRepository implements EventSessionRepository {

        private EventSession session;
        private final AtomicInteger sessionIdSeq = new AtomicInteger();

        @Override
        public EventSession newSession(String headEventId) {
            session = EventSession.builder()
                .sessionId("s" + sessionIdSeq.incrementAndGet())
                .headEventId(headEventId)
                .status(EventSessionStatus.idle)
                .build();
            return session;
        }

        @Override
        public EventSession get(String sessionId) {
            return session != null && Objects.equals(session.getSessionId(), sessionId) ? session : null;
        }

        @Override
        public boolean casHeadEventId(String sessionId, String oldHeadEventId, String newHeadEventId,
                                      EventSessionStatus newStatus, String runningTurnId) {
            if (session == null || !Objects.equals(session.getSessionId(), sessionId)
                || !Objects.equals(session.getHeadEventId(), oldHeadEventId)) {
                return false;
            }
            session.setHeadEventId(newHeadEventId);
            session.setStatus(newStatus);
            session.setRunningTurnId(runningTurnId);
            session.setLastEventAt(LocalDateTime.now());
            if (newStatus == EventSessionStatus.busy && session.getRunningSince() == null) {
                session.setRunningSince(LocalDateTime.now());
            }
            if (newStatus == EventSessionStatus.idle) {
                session.setRunningSince(null);
            }
            return true;
        }

        @Override
        public void updateSessionConfig(String sessionId, ProviderConfig providerConfig,
                                        ModelRequestConfig modelRequestConfig, Map<String, Object> parameters,
                                        String systemPrompt) {
            session.setProviderConfig(providerConfig);
            session.setModelRequestConfig(modelRequestConfig);
            session.setParameters(parameters);
            session.setSystemPrompt(systemPrompt);
        }
    }

    private static class InMemoryEventRepository implements EventRepository {

        private final AtomicInteger eventIdSeq = new AtomicInteger();
        private final Map<String, Event> eventById = new HashMap<>();
        private final List<Event> events = new ArrayList<>();

        @Override
        public String generateEventId() {
            return "e" + eventIdSeq.incrementAndGet();
        }

        @Override
        public void append(Event event) {
            eventById.put(event.getEventId(), event);
            events.add(event);
        }

        @Override
        public Event get(String eventId) {
            return eventById.get(eventId);
        }
    }

    private static class InMemoryAgentRequestTaskRepository implements AgentRequestTaskRepository {

        private final AtomicInteger taskIdSeq = new AtomicInteger();
        private final List<AgentRequestTask> tasks = new ArrayList<>();

        @Override
        public String generateTaskId() {
            return "task_" + taskIdSeq.incrementAndGet();
        }

        @Override
        public AgentRequestTask add(AgentRequestTask agentRequestTask) {
            tasks.add(agentRequestTask);
            return agentRequestTask;
        }

        @Override
        public List<AgentRequestTask> listPendingForUpdate(String sessionId, int limit) {
            return tasks.stream()
                .filter(task -> Objects.equals(task.getSessionId(), sessionId))
                .filter(task -> !task.isConsume())
                .limit(limit)
                .toList();
        }

        @Override
        public boolean consumeAll(List<String> taskIdList, String turnId) {
            tasks.stream()
                .filter(task -> taskIdList.contains(task.getTaskId()))
                .forEach(task -> {
                    task.setConsume(true);
                    task.setTurnId(turnId);
                });
            return true;
        }

        @Override
        public boolean hasPending(String sessionId) {
            return tasks.stream()
                .anyMatch(task -> Objects.equals(task.getSessionId(), sessionId) && !task.isConsume());
        }
    }

}
