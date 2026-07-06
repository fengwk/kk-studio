package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 负责按当前 session 配置装配一个可执行的 embedded agent runtime。 */
@AllArgsConstructor
@Component
final class EmbeddedAgentRuntimeLoader {

  private static final String DEFAULT_VARIANT = "default";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final ProviderManager providerManager;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;
  private final EmbeddedAgentRuntimeMetadataFactory runtimeMetadataFactory;

  @Qualifier("agentToolWorkerExecutorService")
  private final ExecutorService agentToolWorkerExecutorService;

  @Qualifier("agentRuntimeScheduledExecutorService")
  private final ScheduledExecutorService agentRuntimeScheduledExecutorService;

  Agent load(String runId, String sessionId, AgentEventHandler eventHandler) {
    AgentSession session = requireSession(sessionId);
    AgentDefinition agentDefinition = requireAgentDefinition(session.getAgentId());
    AgentProvider agentProvider = requireProvider(agentDefinition.getDefaultProviderId());
    AgentModel agentModel = requireModel(agentDefinition.getDefaultModelId());
    ProviderInfo providerInfo = runtimeMetadataFactory.toProviderInfo(agentProvider);

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
    UserRequestQueue userRequestQueue = new InMemoryUserRequestQueue();
    AgentFactory agentFactory = new AgentFactory();
    return agentFactory.load(
        sessionId,
        agentDefinition.getName(),
        agentProvider.getName(),
        agentModel.getName(),
        defaultVariant(agentDefinition.getDefaultVariant()),
        userRequestQueue,
        eventHandler,
        toolRegistry,
        toolCallExecutor,
        sessionManager,
        new CoreSessionEventMessageProjector(),
        new SingleAgentRegistry(
            runtimeMetadataFactory.toAgentInfo(
                agentDefinition, agentProvider.getName(), agentModel.getName())),
        new SingleModelRegistry(
            runtimeMetadataFactory.toModelInfo(agentModel, agentProvider.getName())),
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

  private AgentSession requireSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return session;
  }

  private AgentDefinition requireAgentDefinition(long agentId) {
    AgentDefinition agentDefinition = agentDefinitionRepository.getById(agentId);
    if (agentDefinition == null) {
      throw new IllegalArgumentException("agent not found: " + agentId);
    }
    return agentDefinition;
  }

  private AgentProvider requireProvider(long providerId) {
    AgentProvider provider = agentProviderRepository.getById(providerId);
    if (provider == null) {
      throw new IllegalArgumentException("provider config not found: " + providerId);
    }
    return provider;
  }

  private AgentModel requireModel(long modelId) {
    AgentModel model = agentModelRepository.getById(modelId);
    if (model == null) {
      throw new IllegalArgumentException("model config not found: " + modelId);
    }
    return model;
  }

  private String defaultVariant(String variant) {
    return variant != null && !variant.isBlank() ? variant : DEFAULT_VARIANT;
  }
}
