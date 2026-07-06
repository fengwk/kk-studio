package fun.fengwk.kkstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author fengwk
 */
public class AgentFactoryTest {

  @Test
  public void testLoadBuildsAgentFromSessionBranch() {
    InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    InMemorySessionEventRepository sessionEventRepository = new InMemorySessionEventRepository();
    SessionManager sessionManager =
        new SessionManagerImpl(sessionRepository, sessionEventRepository);

    Session session = Session.newSession();
    sessionRepository.save(session);
    Branch branch = session.currentBranch();

    SetAgentInfoPayload setAgentInfoPayload = new SetAgentInfoPayload();
    setAgentInfoPayload.setAgentName("assistant");
    setAgentInfoPayload.setSystemPrompt("sys");
    SessionEvent agentEvent =
        SessionEvent.newEvent(
            session.getSessionId(),
            SessionEventType.set_agent_info,
            branch.headEventId(),
            setAgentInfoPayload);
    branch = sessionManager.appendEvent(branch, agentEvent);

    SetModelInfoPayload setModelInfoPayload = new SetModelInfoPayload();
    setModelInfoPayload.setProvider("openai");
    setModelInfoPayload.setModel("gpt-test");
    setModelInfoPayload.setVariant("high");
    SessionEvent modelEvent =
        SessionEvent.newEvent(
            session.getSessionId(),
            SessionEventType.set_model_info,
            branch.headEventId(),
            setModelInfoPayload);
    branch = sessionManager.appendEvent(branch, modelEvent);

    sessionRepository.compareAndSetCurrentHeadEventId(
        session.getSessionId(), SessionEvent.ROOT_EVENT_ID, branch.headEventId());

    AgentFactory agentFactory = new AgentFactory();
    AgentScheduler scheduler = (delay, task) -> () -> {};
    Agent agent =
        agentFactory.load(
            session.getSessionId(),
            "assistant",
            "openai",
            "gpt-test",
            "high",
            new InMemoryUserRequestQueue(),
            event -> {},
            new DefaultToolRegistry(),
            new ToolCallExecutor(new DirectExecutorService(), scheduler),
            sessionManager,
            new DefaultSessionEventMessageProjector(),
            new InMemoryAgentRegistry(),
            new InMemoryModelRegistry(),
            new InMemoryProviderRegistry(),
            new ProviderManagerImpl(),
            scheduler,
            ModelRetryConfig.builder()
                .maxRetries(1)
                .baseDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(10))
                .multiplier(2D)
                .build());

    assertNotNull(agent);
    assertEquals(session.getSessionId(), agent.getSession().getSessionId());
    assertEquals(branch.headEventId(), agent.getBranch().headEventId());
    assertEquals("assistant", agent.getCurrentAgentInfo().getAgentName());
    assertEquals("sys", agent.getCurrentAgentInfo().getSystemPrompt());
    assertEquals("openai", agent.getCurrentModelInfo().getProvider());
    assertEquals("gpt-test", agent.getCurrentModelInfo().getModel());
  }

  private static class InMemorySessionRepository implements SessionRepository {

    private final Map<String, Session> sessionById = new HashMap<>();

    @Override
    public Session getSession(String sessionId) {
      return sessionById.get(sessionId);
    }

    @Override
    public boolean compareAndSetCurrentHeadEventId(
        String sessionId, String expectedHeadEventId, String newHeadEventId) {
      Session session = sessionById.get(sessionId);
      if (session == null) {
        return false;
      }
      if (!expectedHeadEventId.equals(session.getCurrentHeadEventId())) {
        return false;
      }
      session.setCurrentHeadEventId(newHeadEventId);
      return true;
    }

    private void save(Session session) {
      sessionById.put(session.getSessionId(), session);
    }
  }

  private static class DirectExecutorService extends AbstractExecutorService {

    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return Collections.emptyList();
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

  private static class InMemorySessionEventRepository implements SessionEventRepository {

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

  private static class InMemoryUserRequestQueue implements UserRequestQueue {

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

  private static class InMemoryAgentRegistry implements AgentRegistry {

    @Override
    public void registerAgent(AgentInfo agentInfo) {}

    @Override
    public AgentInfo getAgent(String name) {
      return AgentInfo.builder()
          .name(name)
          .systemPrompt("sys")
          .defaultProvider("openai")
          .defaultModel("gpt-test")
          .defaultVariant("high")
          .build();
    }
  }

  private static class InMemoryModelRegistry implements ModelRegistry {

    @Override
    public void registerModel(ModelInfo modelInfo) {}

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

  private static class InMemoryProviderRegistry implements ProviderRegistry {

    @Override
    public void registerProvider(String provider, ProviderInfo providerInfo) {}

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
}
