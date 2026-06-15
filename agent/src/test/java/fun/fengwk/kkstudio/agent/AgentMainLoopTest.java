package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionContext;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author fengwk
 */
public class AgentMainLoopTest {

    @Test
    public void testRetryWritesNewAssistantLifecycle() {
        RecordingContext context = new RecordingContext();
        context.provider.enqueue(handler -> handler.onError(new RuntimeException("timeout"), noopHandle()));
        context.provider.enqueue(handler -> handler.onComplete(AssistantResponse.builder()
            .text("done")
            .metadata(new AssistantMetadata())
            .build(), noopHandle()));

        Agent agent = context.newAgent();
        agent.submit(UserRequest.userRequest("hello"));

        assertEquals(List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.error,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end
        ), context.eventTypes());
        AssistantStartPayload firstStart = (AssistantStartPayload) context.events.get(2).getPayload();
        AssistantStartPayload secondStart = (AssistantStartPayload) context.events.get(4).getPayload();
        assertEquals(List.of("hello"), firstStart.getUserMessages());
        assertEquals(List.of(), secondStart.getUserMessages());
        assertEquals(AgentStatus.idle, agent.getStatus());
    }

    @Test
    public void testToolLoopStartsNextAssistant() {
        RecordingContext context = new RecordingContext();
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId("call_1");
        toolCall.setToolName("bash");
        toolCall.setArguments("{}");
        context.provider.enqueue(handler -> handler.onComplete(AssistantResponse.builder()
            .toolCalls(List.of(toolCall))
            .metadata(new AssistantMetadata())
            .build(), noopHandle()));
        context.provider.enqueue(handler -> handler.onComplete(AssistantResponse.builder()
            .text("final")
            .metadata(new AssistantMetadata())
            .build(), noopHandle()));
        context.toolRegistry.registerTool("bash", ToolInfo.builder().name("bash").description("bash").inputSchema("{}").build(), new Tool() {
            @Override
            public ToolExecutionHandle asyncExecute(dev.langchain4j.agent.tool.ToolExecutionRequest toolExecutionRequest,
                                                    ToolExecutionHandler toolExecutionHandler) {
                ToolContentDelta delta = new ToolContentDelta();
                delta.setType(ToolContentType.text);
                delta.setText("ok");
                IndexedToolContentDelta indexed = new IndexedToolContentDelta();
                indexed.setIndex(0);
                indexed.setContentDelta(delta);
                toolExecutionHandler.onPartial(List.of(indexed), new ToolExecutionContext("call_1", "bash", "{}", noopToolHandle()));

                ToolContent content = new ToolContent();
                content.setType(ToolContentType.text);
                content.setText("ok");
                toolExecutionHandler.onComplete(List.of(content), new ToolExecutionContext("call_1", "bash", "{}", noopToolHandle()));
                return noopToolHandle();
            }
        });

        Agent agent = context.newAgent();
        agent.submit(UserRequest.userRequest("hello"));

        assertEquals(List.of(
            SessionEventType.set_agent_info,
            SessionEventType.set_model_info,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end,
            SessionEventType.tool_start,
            SessionEventType.tool_delta,
            SessionEventType.tool_end,
            SessionEventType.assistant_start,
            SessionEventType.assistant_delta,
            SessionEventType.assistant_end
        ), context.eventTypes());
        AssistantStartPayload secondStart = (AssistantStartPayload) context.events.get(8).getPayload();
        assertEquals(List.of(), secondStart.getUserMessages());
        assertEquals(AgentStatus.idle, agent.getStatus());
    }

    private static AssistantResponseHandle noopHandle() {
        return new AssistantResponseHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private static ToolExecutionHandle noopToolHandle() {
        return () -> {
        };
    }

    private static final class RecordingContext {
        private final List<SessionEvent> events = new ArrayList<>();
        private final InMemoryUserRequestQueue userRequestQueue = new InMemoryUserRequestQueue();
        private final InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry();
        private final StubProvider provider = new StubProvider();

        private Agent newAgent() {
            InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
            InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
            SessionManager sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
            Session session = Session.newSession();
            sessionRepository.save(session);

            AgentFactory agentFactory = new AgentFactory();
            return agentFactory.load(
                session.getSessionId(),
                "assistant",
                "openai",
                "gpt-test",
                "high",
                userRequestQueue,
                events::add,
                toolRegistry,
                sessionManager,
                new DefaultSessionEventMessageProjector(),
                new StubAgentRegistry(),
                new StubModelRegistry(),
                new StubProviderRegistry(),
                providerInfo -> provider,
                (delay, task) -> {
                    task.run();
                    return () -> {
                    };
                },
                ModelRetryConfig.builder()
                    .maxRetries(1)
                    .baseDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .multiplier(2D)
                    .build());
        }

        private List<SessionEventType> eventTypes() {
            return events.stream().map(SessionEvent::getEventType).toList();
        }
    }

    private static final class StubProvider implements Provider {
        private final Queue<Consumer<AssistantResponseHandler>> scripts = new ArrayDeque<>();

        private void enqueue(Consumer<AssistantResponseHandler> script) {
            scripts.offer(script);
        }

        @Override
        public ProviderType getProviderType() {
            return ProviderType.openai;
        }

        @Override
        public dev.langchain4j.model.chat.request.ChatRequest buildChatRequest(List<dev.langchain4j.data.message.ChatMessage> chatMessageList,
                                                                               ModelRequestConfig modelConfig) {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.langchain4j.model.chat.StreamingChatModel getChatModel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata toAssistantMetadata(dev.langchain4j.model.chat.response.ChatResponseMetadata metadata) {
            return Provider.toCommonAssistantMetadata(metadata);
        }

        @Override
        public AssistantResponseHandle asyncChat(List<dev.langchain4j.data.message.ChatMessage> chatMessageList,
                                                 ModelRequestConfig modelConfig,
                                                 AssistantResponseHandler handler) {
            Consumer<AssistantResponseHandler> script = scripts.poll();
            if (script != null) {
                script.accept(handler);
            }
            return noopHandle();
        }
    }

    private static final class InMemoryToolRegistry implements ToolRegistry {
        private final Map<String, ToolInfo> infos = new HashMap<>();
        private final Map<String, Tool> tools = new HashMap<>();

        @Override
        public void registerTool(String name, ToolInfo toolInfo, Tool tool) {
            infos.put(name, toolInfo);
            tools.put(name, tool);
        }

        @Override
        public ToolInfo getToolInfo(String name) {
            return infos.get(name);
        }

        @Override
        public Tool getTool(String name) {
            return tools.get(name);
        }
    }

    private static final class InMemoryUserRequestQueue implements UserRequestQueue {
        private final List<UserRequest> requests = new ArrayList<>();

        @Override
        public void submit(UserRequest userRequest) {
            requests.add(userRequest);
        }

        @Override
        public List<UserRequest> pollAll() {
            List<UserRequest> copied = List.copyOf(requests);
            requests.clear();
            return copied;
        }

        @Override
        public boolean isEmpty() {
            return requests.isEmpty();
        }
    }

    private static final class StubAgentRegistry implements AgentRegistry {
        @Override
        public void registerAgent(AgentInfo agentInfo) {
        }

        @Override
        public AgentInfo getAgent(String name) {
            return AgentInfo.builder()
                .name(name)
                .systemPrompt("sys")
                .defaultProvider("openai")
                .defaultModel("gpt-test")
                .defaultVariant("high")
                .tools(List.of("bash"))
                .build();
        }
    }

    private static final class StubModelRegistry implements ModelRegistry {
        @Override
        public void registerModel(ModelInfo modelInfo) {
        }

        @Override
        public ModelInfo getModel(String provider, String model) {
            return ModelInfo.builder()
                .provider(provider)
                .name(model)
                .defaultVariant("high")
                .variants(List.of(Variant.builder().name("high").build()))
                .build();
        }
    }

    private static final class StubProviderRegistry implements ProviderRegistry {
        @Override
        public void registerProvider(String provider, ProviderInfo providerInfo) {
        }

        @Override
        public ProviderInfo getProviderInfo(String provider) {
            return ProviderInfo.builder()
                .providerType(ProviderType.openai)
                .baseUrl("http://localhost")
                .apiKey("test")
                .timeout(Duration.ofSeconds(30))
                .streamIdleTimeout(Duration.ofSeconds(30))
                .build();
        }
    }

    private static final class InMemorySessionRepository implements SessionRepository {
        private final Map<String, Session> sessionById = new HashMap<>();

        @Override
        public Session getSession(String sessionId) {
            return sessionById.get(sessionId);
        }

        @Override
        public boolean compareAndSetCurrentHeadEventId(String sessionId, String expectedHeadEventId, String newHeadEventId) {
            Session session = sessionById.get(sessionId);
            if (session == null || !expectedHeadEventId.equals(session.getCurrentHeadEventId())) {
                return false;
            }
            session.setCurrentHeadEventId(newHeadEventId);
            return true;
        }

        private void save(Session session) {
            sessionById.put(session.getSessionId(), session);
        }
    }

    private static final class InMemorySessionEventRepository implements SessionEventRepository {
        private final List<SessionEvent> events = new ArrayList<>();

        @Override
        public List<SessionEvent> listBySessionId(String sessionId) {
            return events.stream().filter(event -> sessionId.equals(event.getSessionId())).toList();
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.add(event);
        }
    }

}
