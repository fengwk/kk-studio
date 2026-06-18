package fun.fengwk.kkstudio.agent.live;

import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Agent 多轮真实环境测试。
 *
 * <p>该测试走 Anthropic 兼容协议，并固定使用 MiniMax-M2.7，以较低成本验证 Agent 跨用户请求上下文重放。</p>
 *
 * @author fengwk
 */
@Slf4j
@Tag("agent-live")
public class AnthropicAgentMultiTurnLiveTest {

    private static final String PROVIDER = "anthropic";
    private static final String MODEL = "MiniMax-M2.7";
    private static final String VARIANT = "live";
    private static final String BASE_URL_ENV = "TEST_ANTHROPIC_BASE_URL";
    private static final String API_KEY_ENV = "TEST_ANTHROPIC_API_KEY";

    /**
     * 校验真实 Agent 两轮用户请求会把首轮 user/assistant 上下文带入第二轮模型调用。
     */
    @Test
    @Timeout(180)
    public void shouldReplayPreviousTurnsWithAnthropicMinimax() throws Exception {
        LiveContext context = new LiveContext(providerInfo());
        Agent agent = context.newAgent();

        agent.submit(UserRequest.userRequest("请记住暗号 WREN-42。只回复 READY，不要解释。"));
        awaitAssistantEnds(agent, context, 1);
        String firstAnswer = latestAssistantText(agent);
        log.info("[agent live] firstAnswer={}", firstAnswer);

        agent.submit(UserRequest.userRequest("根据前文，只输出暗号本身，不要解释。"));
        awaitAssistantEnds(agent, context, 2);
        String secondAnswer = latestAssistantText(agent);
        log.info("[agent live] secondAnswer={}", secondAnswer);

        assertEquals(2, context.providerManager.capturedMessages.size());
        List<AgentMessage> secondCallMessages = context.providerManager.capturedMessages.get(1);
        assertTrue(secondCallMessages.stream()
            .filter(AgentUserMessage.class::isInstance)
            .map(AgentUserMessage.class::cast)
            .anyMatch(message -> message.text().contains("WREN-42")));
        assertTrue(secondCallMessages.stream()
            .anyMatch(AgentAssistantMessage.class::isInstance));
        AgentUserMessage lastMessage = assertInstanceOf(AgentUserMessage.class, secondCallMessages.get(secondCallMessages.size() - 1));
        assertEquals("根据前文，只输出暗号本身，不要解释。", lastMessage.text());
    }

    private ProviderInfo providerInfo() {
        String baseUrl = System.getenv(BASE_URL_ENV);
        String apiKey = System.getenv(API_KEY_ENV);
        Assumptions.assumeTrue(baseUrl != null && !baseUrl.isBlank(), () -> "missing env: " + BASE_URL_ENV);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), () -> "missing env: " + API_KEY_ENV);
        return ProviderInfo.builder()
            .providerType(ProviderType.anthropic)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(90))
            .streamIdleTimeout(Duration.ofSeconds(90))
            .build();
    }

    private void awaitAssistantEnds(Agent agent, LiveContext context, int expectedAssistantEnds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            List<SessionEvent> eventSnapshot = context.eventSnapshot();
            long assistantEnds = eventSnapshot.stream()
                .filter(event -> event.getEventType() == SessionEventType.assistant_end)
                .count();
            if (assistantEnds >= expectedAssistantEnds && agent.getStatus() == AgentStatus.idle) {
                assertNoAssistantError(eventSnapshot);
                return;
            }
            Thread.sleep(100L);
        }
        fail("agent live test timed out, status=" + agent.getStatus() + ", events=" + context.eventTypes());
    }

    private void assertNoAssistantError(List<SessionEvent> events) {
        List<SessionEvent> assistantErrors = events.stream()
            .filter(event -> event.getEventType() == SessionEventType.assistant_error)
            .toList();
        assertTrue(assistantErrors.isEmpty(), () -> "assistant errors: " + assistantErrors);
    }

    private String latestAssistantText(Agent agent) {
        List<AgentMessage> messages = agent.getProjectedMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AgentAssistantMessage assistantMessage && assistantMessage.text() != null) {
                return assistantMessage.text();
            }
        }
        fail("missing assistant message in projection: " + messages);
        return null;
    }

    private static final class LiveContext {
        private final List<SessionEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final CapturingProviderManager providerManager;

        private LiveContext(ProviderInfo providerInfo) {
            this.providerManager = new CapturingProviderManager(providerInfo);
        }

        private Agent newAgent() {
            InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
            InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
            SessionManager sessionManager = new SessionManagerImpl(sessionRepository, sessionEventRepository);
            Session session = Session.newSession();
            sessionRepository.save(session);
            AgentScheduler scheduler = (delay, task) -> {
                task.run();
                return () -> {
                };
            };
            return new AgentFactory().load(
                session.getSessionId(),
                "live-agent",
                PROVIDER,
                MODEL,
                VARIANT,
                new InMemoryUserRequestQueue(),
                new RecordingAgentEventHandler(events),
                new DefaultToolRegistry(),
                new ToolCallExecutor(new DirectExecutorService(), scheduler),
                sessionManager,
                new DefaultSessionEventMessageProjector(),
                new LiveAgentRegistry(),
                new LiveModelRegistry(),
                new LiveProviderRegistry(providerManager.providerInfo),
                providerManager,
                scheduler,
                ModelRetryConfig.builder().maxRetries(0).build());
        }

        private List<SessionEventType> eventTypes() {
            return eventSnapshot().stream().map(SessionEvent::getEventType).toList();
        }

        private List<SessionEvent> eventSnapshot() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }
    }

    private record RecordingAgentEventHandler(List<SessionEvent> events) implements AgentEventHandler {
        @Override
        public void onEvent(SessionEvent event) {
            events.add(event);
        }
    }

    private static final class CapturingProviderManager implements ProviderManager {
        private final ProviderInfo providerInfo;
        private final ProviderManager delegateProviderManager = new ProviderManagerImpl();
        private final List<List<AgentMessage>> capturedMessages = Collections.synchronizedList(new ArrayList<>());
        private Provider provider;

        private CapturingProviderManager(ProviderInfo providerInfo) {
            this.providerInfo = providerInfo;
        }

        @Override
        public Provider getProvider(ProviderInfo providerInfo) {
            if (provider == null) {
                provider = new CapturingProvider(delegateProviderManager.getProvider(providerInfo), capturedMessages);
            }
            return provider;
        }
    }

    private record CapturingProvider(Provider delegate, List<List<AgentMessage>> capturedMessages) implements Provider {
        @Override
        public ProviderType getProviderType() {
            return delegate.getProviderType();
        }

        @Override
        public AssistantResponseHandle asyncChat(List<AgentMessage> messages,
                                                  ModelInfo modelInfo,
                                                  Variant variant,
                                                  List<ToolInfo> toolInfos,
                                                  AssistantResponseHandler handler) {
            capturedMessages.add(List.copyOf(messages));
            return delegate.asyncChat(messages, modelInfo, variant, toolInfos, handler);
        }
    }

    private static final class LiveAgentRegistry implements AgentRegistry {
        @Override
        public void registerAgent(AgentInfo agentInfo) {
        }

        @Override
        public AgentInfo getAgent(String name) {
            return AgentInfo.builder()
                .name(name)
                .systemPrompt("你是一个严格遵循指令的测试助手。回答必须简短，不要添加解释。")
                .defaultProvider(PROVIDER)
                .defaultModel(MODEL)
                .defaultVariant(VARIANT)
                .tools(List.of())
                .build();
        }
    }

    private static final class LiveModelRegistry implements ModelRegistry {
        @Override
        public void registerModel(ModelInfo modelInfo) {
        }

        @Override
        public ModelInfo getModel(String provider, String model) {
            return ModelInfo.builder()
                .provider(provider)
                .name(model)
                .defaultVariant(VARIANT)
                .variants(List.of(Variant.builder()
                    .name(VARIANT)
                    .temperature(0D)
                    .maxOutputTokens(64)
                    .build()))
                .build();
        }
    }

    private record LiveProviderRegistry(ProviderInfo providerInfo) implements ProviderRegistry {
        @Override
        public void registerProvider(String provider, ProviderInfo providerInfo) {
        }

        @Override
        public ProviderInfo getProviderInfo(String provider) {
            return providerInfo;
        }
    }

    private static final class InMemoryUserRequestQueue implements UserRequestQueue {
        private final Queue<UserRequest> requests = new ArrayDeque<>();

        @Override
        public void submit(UserRequest userRequest) {
            requests.offer(userRequest);
        }

        @Override
        public List<UserRequest> pollAll() {
            List<UserRequest> result = new ArrayList<>(requests);
            requests.clear();
            return result;
        }

        @Override
        public boolean isEmpty() {
            return requests.isEmpty();
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class InMemorySessionRepository implements SessionRepository {
        private final Map<String, Session> sessionById = Collections.synchronizedMap(new HashMap<>());

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
        private final List<SessionEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<SessionEvent> listBySessionId(String sessionId) {
            return events.stream()
                .filter(event -> sessionId.equals(event.getSessionId()))
                .toList();
        }

        @Override
        public void appendEvent(SessionEvent event) {
            events.add(event);
        }
    }

}
