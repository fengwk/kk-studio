package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.UserRequest;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;
import fun.fengwk.kkstudio.agent.session.repo.SessionEventRepository;
import fun.fengwk.kkstudio.agent.session.repo.SessionRepository;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
@Slf4j
public class EmbeddedAgentRunRuntimeService implements AgentRunRuntimeService {

  private static final String DEFAULT_HEAD_NAME = "default";
  private static final String DEFAULT_VARIANT = "default";
  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";
  private static final Duration RUN_IDLE_TIMEOUT = Duration.ofMinutes(3);
  private static final long RUN_IDLE_POLL_MILLIS = 50L;
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final AgentRunService agentRunService;
  private final ProviderManager providerManager;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;

  @Qualifier("agentRunTaskExecutor")
  private final Executor agentRunTaskExecutor;

  @Qualifier("agentToolWorkerExecutorService")
  private final ExecutorService agentToolWorkerExecutorService;

  @Qualifier("agentRuntimeScheduledExecutorService")
  private final ScheduledExecutorService agentRuntimeScheduledExecutorService;

  @Override
  public void scheduleQueuedRun(String runId, String sessionId, String content) {
    agentRunTaskExecutor.execute(() -> executeQueuedRun(runId, sessionId, content));
  }

  @Override
  public void executeQueuedRun(String runId, String sessionId, String content) {
    // 不再包外层事务：agent 主循环内的每条 event（assistant_delta 等）
    // 都是单条 INSERT，由 MyBatis/auto-commit 立即对外可见，
    // 这样 SSE 端在下个 250ms 轮询周期就能拿到新的 delta，而不是等 run 结束才一次性 commit。
    // event 之间没有原子要求，run 状态机由 agentRunService 单条 UPDATE 维护，
    // 崩溃遗留的 stale run 由 StaleRunReconciler 启动时清理。
    runOnce(runId, sessionId, content);
  }

  private void runOnce(String runId, String sessionId, String content) {
    if (!agentRunService.markRunning(runId)) {
      return;
    }

    try {
      AgentSession session = agentSessionRepository.getBySessionId(sessionId);
      if (session == null) {
        throw new IllegalArgumentException("session not found: " + sessionId);
      }
      AgentDefinition agentDefinition = agentDefinitionRepository.getById(session.getAgentId());
      if (agentDefinition == null) {
        throw new IllegalArgumentException("agent not found: " + session.getAgentId());
      }
      RecordingAgentEventHandler eventHandler = new RecordingAgentEventHandler();
      Agent agent = reloadAgentWithEventHandler(runId, sessionId, agentDefinition, eventHandler);
      agent.submit(UserRequest.userRequest(content));
      if (waitForAgentIdle(agent)) {
        if (eventHandler.isTerminalFailure()) {
          agentRunService.markFailed(runId);
        } else {
          agentRunService.markSucceeded(runId);
        }
      } else {
        log.warn("Execute queued run timed out, runId: {}, sessionId: {}", runId, sessionId);
        agentRunService.markFailed(runId);
      }
    } catch (Exception e) {
      log.error("Execute queued run failed, runId: {}, sessionId: {}", runId, sessionId, e);
      e.printStackTrace();
      agentRunService.markFailed(runId);
    }
  }

  private boolean waitForAgentIdle(Agent agent) {
    long deadline = System.nanoTime() + RUN_IDLE_TIMEOUT.toNanos();
    while (agent.getStatus() != AgentStatus.idle) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(RUN_IDLE_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  private Agent reloadAgentWithEventHandler(
      String runId,
      String sessionId,
      AgentDefinition agentDefinition,
      AgentEventHandler agentEventHandler) {
    AgentProvider agentProvider =
        agentProviderRepository.getById(agentDefinition.getDefaultProviderId());
    if (agentProvider == null) {
      throw new IllegalArgumentException(
          "provider config not found: " + agentDefinition.getDefaultProviderId());
    }
    AgentModel agentModel = agentModelRepository.getById(agentDefinition.getDefaultModelId());
    if (agentModel == null) {
      throw new IllegalArgumentException(
          "model config not found: " + agentDefinition.getDefaultModelId());
    }
    ProviderInfo providerInfo = toProviderInfo(agentProvider);

    ToolRegistry toolRegistry = new DefaultToolRegistry();
    AgentScheduler agentScheduler =
        new ScheduledExecutorAgentScheduler(agentRuntimeScheduledExecutorService);
    ToolCallExecutor toolCallExecutor =
        new ToolCallExecutor(agentToolWorkerExecutorService, agentScheduler);
    SessionManager sessionManager =
        new SessionManagerImpl(
            new CoreSessionRepositoryAdapter(
                agentSessionRepository, agentSessionHeadRepository, transactionTemplate),
            new CoreSessionEventRepositoryAdapter(
                runId, agentSessionEventRepository, objectMapper));
    AgentFactory agentFactory = new AgentFactory();
    return agentFactory.load(
        sessionId,
        agentDefinition.getName(),
        agentProvider.getName(),
        agentModel.getName(),
        firstNonBlank(agentDefinition.getDefaultVariant(), DEFAULT_VARIANT),
        new InMemoryUserRequestQueue(),
        agentEventHandler,
        toolRegistry,
        toolCallExecutor,
        sessionManager,
        new CoreSessionEventMessageProjector(),
        new SingleAgentRegistry(toAgentInfo(agentDefinition)),
        new SingleModelRegistry(toModelInfo(agentModel)),
        new SingleProviderRegistry(agentProvider.getName(), providerInfo),
        providerManager,
        agentScheduler,
        ModelRetryConfig.builder()
            .maxRetries(0)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build());
  }

  private ProviderInfo toProviderInfo(AgentProvider agentProvider) {
    return ProviderInfo.builder()
        .providerType(agentProvider.getProviderType())
        .baseUrl(agentProvider.getBaseUrl())
        .apiKey(agentProvider.getApiKey())
        .timeout(agentProvider.getTimeout())
        .streamIdleTimeout(agentProvider.getStreamIdleTimeout())
        .build();
  }

  private AgentInfo toAgentInfo(AgentDefinition agentDefinition) {
    AgentProvider provider =
        agentProviderRepository.getById(agentDefinition.getDefaultProviderId());
    AgentModel model = agentModelRepository.getById(agentDefinition.getDefaultModelId());
    return AgentInfo.builder()
        .name(agentDefinition.getName())
        .systemPrompt(agentDefinition.getSystemPrompt())
        .defaultProvider(provider == null ? null : provider.getName())
        .defaultModel(model == null ? null : model.getName())
        .defaultVariant(firstNonBlank(agentDefinition.getDefaultVariant(), DEFAULT_VARIANT))
        .tools(parseStringList(agentDefinition.getToolsJson()))
        .subagents(parseStringList(agentDefinition.getSubagentsJson()))
        .skills(parseStringList(agentDefinition.getSkillsJson()))
        .build();
  }

  private ModelInfo toModelInfo(AgentModel agentModel) {
    AgentProvider provider = agentProviderRepository.getById(agentModel.getProviderId());
    return ModelInfo.builder()
        .provider(provider == null ? null : provider.getName())
        .name(agentModel.getName())
        .displayName(agentModel.getName())
        .defaultVariant(firstNonBlank(agentModel.getDefaultVariant(), DEFAULT_VARIANT))
        .variants(parseVariants(agentModel.getVariantsJson()))
        .build();
  }

  private List<Variant> parseVariants(String variantsJson) {
    if (variantsJson == null || variantsJson.isBlank()) {
      return List.of(Variant.builder().name(DEFAULT_VARIANT).build());
    }
    try {
      JsonNode root = objectMapper.readTree(variantsJson);
      if (!root.isArray()) {
        throw new IllegalArgumentException("variantsJson must be a JSON array");
      }
      List<Variant> variants = new ArrayList<>();
      for (JsonNode node : root) {
        variants.add(toVariant(node));
      }
      return variants.isEmpty()
          ? List.of(Variant.builder().name(DEFAULT_VARIANT).build())
          : List.copyOf(variants);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("parse model variantsJson failed", e);
    }
  }

  private Variant toVariant(JsonNode node) {
    Variant.VariantBuilder builder =
        Variant.builder().name(firstNonBlank(textValue(node, "name"), DEFAULT_VARIANT));
    if (node.hasNonNull("maxOutputTokens")) {
      builder.maxOutputTokens(node.get("maxOutputTokens").asInt());
    }
    if (node.hasNonNull("temperature")) {
      builder.temperature(node.get("temperature").asDouble());
    }
    if (node.hasNonNull("topP")) {
      builder.topP(node.get("topP").asDouble());
    }
    if (node.hasNonNull("topK")) {
      builder.topK(node.get("topK").asInt());
    }
    if (node.hasNonNull("frequencyPenalty")) {
      builder.frequencyPenalty(node.get("frequencyPenalty").asDouble());
    }
    if (node.hasNonNull("presencePenalty")) {
      builder.presencePenalty(node.get("presencePenalty").asDouble());
    }
    if (node.hasNonNull("seed")) {
      builder.seed(node.get("seed").asInt());
    }
    if (node.hasNonNull("stopSequences") && node.get("stopSequences").isArray()) {
      List<String> stopSequences = new ArrayList<>();
      for (JsonNode sequence : node.get("stopSequences")) {
        if (sequence.isTextual()) {
          stopSequences.add(sequence.asText());
        }
      }
      builder.stopSequences(List.copyOf(stopSequences));
    }
    if (node.hasNonNull("providerOptions") && node.get("providerOptions").isObject()) {
      try {
        Map<String, Object> providerOptions =
            objectMapper.convertValue(node.get("providerOptions"), MAP_TYPE_REFERENCE);
        builder.providerOptions(providerOptions);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("parse variant providerOptions failed", e);
      }
    }
    return builder.build();
  }

  private List<String> parseStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      if (!root.isArray()) {
        return List.of();
      }
      List<String> result = new ArrayList<>();
      for (JsonNode node : root) {
        if (node.isTextual() && !node.asText().isBlank()) {
          result.add(node.asText());
        }
      }
      return List.copyOf(result);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("parse agent string list failed", e);
    }
  }

  private String textValue(JsonNode node, String fieldName) {
    if (node == null || !node.hasNonNull(fieldName) || !node.get(fieldName).isTextual()) {
      return null;
    }
    return node.get(fieldName).asText();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
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

  private static final class RecordingAgentEventHandler implements AgentEventHandler {

    private SessionEventType lastEventType;

    @Override
    public void onEvent(SessionEvent event) {
      if (event != null) {
        lastEventType = event.getEventType();
      }
    }

    private boolean isTerminalFailure() {
      return lastEventType == SessionEventType.assistant_error
          || lastEventType == SessionEventType.tool_error
          || lastEventType == SessionEventType.abort;
    }
  }

  private static final class CoreSessionEventMessageProjector
      implements SessionEventMessageProjector {

    private final DefaultSessionEventMessageProjector delegate =
        new DefaultSessionEventMessageProjector();

    @Override
    public SessionEventProjection project(List<SessionEvent> branchEvents) {
      return project(branchEvents, false);
    }

    @Override
    public SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
      return project(branchEvents, true);
    }

    private SessionEventProjection project(
        List<SessionEvent> branchEvents, boolean runtimeProjection) {
      if (branchEvents == null) {
        return delegate.project(null);
      }
      List<SessionEvent> filteredEvents =
          branchEvents.stream()
              .filter(
                  event ->
                      !(event instanceof BridgedSessionEvent bridgedEvent
                          && USER_MESSAGE_EVENT_TYPE.equals(bridgedEvent.getRawEventType())))
              .toList();
      return runtimeProjection
          ? delegate.projectForRuntime(filteredEvents)
          : delegate.project(filteredEvents);
    }
  }

  private static final class ScheduledExecutorAgentScheduler implements AgentScheduler {

    private final ScheduledExecutorService scheduledExecutorService;

    private ScheduledExecutorAgentScheduler(ScheduledExecutorService scheduledExecutorService) {
      this.scheduledExecutorService = scheduledExecutorService;
    }

    @Override
    public ScheduledTask schedule(Duration delay, Runnable task) {
      long delayMillis = delay == null ? 0L : Math.max(0L, delay.toMillis());
      ScheduledFuture<?> future =
          scheduledExecutorService.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
      return () -> future.cancel(false);
    }
  }

  private static final class SingleAgentRegistry implements AgentRegistry {

    private final AgentInfo agentInfo;

    private SingleAgentRegistry(AgentInfo agentInfo) {
      this.agentInfo = agentInfo;
    }

    @Override
    public void registerAgent(AgentInfo agentInfo) {
      throw new UnsupportedOperationException("registerAgent is not supported");
    }

    @Override
    public AgentInfo getAgent(String name) {
      return agentInfo != null && agentInfo.getName().equals(name) ? agentInfo : null;
    }
  }

  private static final class SingleModelRegistry implements ModelRegistry {

    private final ModelInfo modelInfo;

    private SingleModelRegistry(ModelInfo modelInfo) {
      this.modelInfo = modelInfo;
    }

    @Override
    public void registerModel(ModelInfo modelInfo) {
      throw new UnsupportedOperationException("registerModel is not supported");
    }

    @Override
    public ModelInfo getModel(String provider, String model) {
      if (modelInfo == null) {
        return null;
      }
      if (!modelInfo.getProvider().equals(provider)) {
        return null;
      }
      return modelInfo.getName().equals(model) ? modelInfo : null;
    }
  }

  private static final class SingleProviderRegistry implements ProviderRegistry {

    private final String providerName;
    private final ProviderInfo providerInfo;

    private SingleProviderRegistry(String providerName, ProviderInfo providerInfo) {
      this.providerName = providerName;
      this.providerInfo = providerInfo;
    }

    @Override
    public void registerProvider(String provider, ProviderInfo providerInfo) {
      throw new UnsupportedOperationException("registerProvider is not supported");
    }

    @Override
    public ProviderInfo getProviderInfo(String provider) {
      return providerName != null && providerName.equals(provider) ? providerInfo : null;
    }
  }

  private static final class CoreSessionRepositoryAdapter implements SessionRepository {

    private final AgentSessionRepository agentSessionRepository;
    private final AgentSessionHeadRepository agentSessionHeadRepository;
    private final TransactionTemplate transactionTemplate;

    private CoreSessionRepositoryAdapter(
        AgentSessionRepository agentSessionRepository,
        AgentSessionHeadRepository agentSessionHeadRepository,
        TransactionTemplate transactionTemplate) {
      this.agentSessionRepository = agentSessionRepository;
      this.agentSessionHeadRepository = agentSessionHeadRepository;
      this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Session getSession(String sessionId) {
      AgentSession session = agentSessionRepository.getBySessionId(sessionId);
      if (session == null) {
        return null;
      }
      Session agentSession = new Session();
      agentSession.setSessionId(session.getSessionId());
      agentSession.setCurrentHeadEventId(session.getCurrentHeadEventId());
      return agentSession;
    }

    @Override
    public boolean compareAndSetCurrentHeadEventId(
        String sessionId, String expectedHeadEventId, String newHeadEventId) {
      Boolean updated =
          transactionTemplate.execute(
              status -> {
                LocalDateTime now = LocalDateTime.now();
                if (!agentSessionRepository.compareAndSetCurrentHeadEventId(
                    sessionId, expectedHeadEventId, newHeadEventId, now)) {
                  return false;
                }
                if (!agentSessionHeadRepository.updateHeadEventId(
                    sessionId, DEFAULT_HEAD_NAME, newHeadEventId)) {
                  throw new IllegalStateException("update session head failed");
                }
                return true;
              });
      return Boolean.TRUE.equals(updated);
    }
  }

  private static final class CoreSessionEventRepositoryAdapter implements SessionEventRepository {

    private final String runId;
    private final AgentSessionEventRepository agentSessionEventRepository;
    private final AgentSessionEventBridge eventBridge;

    private CoreSessionEventRepositoryAdapter(
        String runId,
        AgentSessionEventRepository agentSessionEventRepository,
        ObjectMapper objectMapper) {
      this.runId = runId;
      this.agentSessionEventRepository = agentSessionEventRepository;
      this.eventBridge = new AgentSessionEventBridge(objectMapper);
    }

    @Override
    public List<SessionEvent> listBySessionId(String sessionId) {
      return agentSessionEventRepository.listBySessionId(sessionId).stream()
          .map(eventBridge::toAgentSessionEvent)
          .toList();
    }

    @Override
    public void appendEvent(SessionEvent event) {
      AgentSessionEvent coreEvent = new AgentSessionEvent();
      coreEvent.setId(AgentIdGenerator.nextEventId());
      coreEvent.setEventId(event.getEventId());
      coreEvent.setSessionId(event.getSessionId());
      coreEvent.setParentEventId(event.getParentEventId());
      coreEvent.setRunId(runId);
      coreEvent.setEventType(event.getEventType().name());
      coreEvent.setPayloadType(event.getEventType().name());
      coreEvent.setPayloadJson(eventBridge.serialize(event.getPayload()));
      coreEvent.setCreateTime(event.getCreateTime());
      if (!agentSessionEventRepository.add(coreEvent)) {
        throw new IllegalStateException("append runtime session event failed");
      }
    }
  }

  private static final class AgentSessionEventBridge {

    private final ObjectMapper objectMapper;

    private AgentSessionEventBridge(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    private String serialize(Payload payload) {
      try {
        return objectMapper.writeValueAsString(payload);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("serialize runtime payload failed", e);
      }
    }

    private SessionEvent toAgentSessionEvent(AgentSessionEvent coreEvent) {
      SessionEvent agentEvent = new BridgedSessionEvent(coreEvent.getEventType());
      agentEvent.setSessionId(coreEvent.getSessionId());
      agentEvent.setEventId(coreEvent.getEventId());
      agentEvent.setParentEventId(coreEvent.getParentEventId());
      agentEvent.setCreateTime(coreEvent.getCreateTime());

      SessionEventType eventType = resolveEventType(coreEvent.getEventType());
      agentEvent.setEventType(eventType);
      if (eventType != null) {
        agentEvent.setPayload(deserialize(eventType, coreEvent.getPayloadJson()));
      }
      return agentEvent;
    }

    private SessionEventType resolveEventType(String eventType) {
      if (eventType == null || eventType.isBlank()) {
        return null;
      }
      try {
        return SessionEventType.valueOf(eventType);
      } catch (IllegalArgumentException ignored) {
        return null;
      }
    }

    private Payload deserialize(SessionEventType eventType, String payloadJson) {
      if (payloadJson == null || payloadJson.isBlank()) {
        return null;
      }
      Class<? extends Payload> payloadType =
          switch (eventType) {
            case set_agent_info -> SetAgentInfoPayload.class;
            case set_model_info -> SetModelInfoPayload.class;
            case assistant_start -> AssistantStartPayload.class;
            case assistant_delta -> AssistantDeltaPayload.class;
            case assistant_end -> AssistantEndPayload.class;
            case assistant_error -> AssistantErrorPayload.class;
            case tool_start -> ToolStartPayload.class;
            case tool_delta -> ToolDeltaPayload.class;
            case tool_end -> ToolEndPayload.class;
            case tool_error -> ToolErrorPayload.class;
            case abort -> AbortPayload.class;
          };
      try {
        return objectMapper.readValue(payloadJson, payloadType);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("deserialize runtime payload failed", e);
      }
    }
  }

  private static final class BridgedSessionEvent extends SessionEvent {

    private final String rawEventType;

    private BridgedSessionEvent(String rawEventType) {
      this.rawEventType = rawEventType;
    }

    private String getRawEventType() {
      return rawEventType;
    }
  }
}
