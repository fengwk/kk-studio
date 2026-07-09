package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.AgentRegistry;
import fun.fengwk.kkstudio.agent.AgentRuntimeConfigResolver;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequestQueue;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.ModelRegistry;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderRegistry;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.SessionManagerImpl;
import fun.fengwk.kkstudio.agent.tool.DefaultToolRegistry;
import fun.fengwk.kkstudio.agent.tool.ToolRegistry;
import fun.fengwk.kkstudio.agent.tool.execution.ToolCallExecutor;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** 负责按当前 session 配置装配一个可执行的 embedded agent runtime。 */
@AllArgsConstructor
@Component
final class EmbeddedAgentRuntimeLoader {
  private final AgentSessionRepository agentSessionRepository;
  private final AgentSessionHeadRepository agentSessionHeadRepository;
  private final AgentSessionEventRepository agentSessionEventRepository;
  private final ProviderManager providerManager;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;
  private final EmbeddedAgentRuntimeReferenceResolver runtimeReferenceResolver;
  private final EmbeddedAgentRuntimeMetadataFactory runtimeMetadataFactory;

  @Qualifier("agentToolWorkerExecutorService")
  private final ExecutorService agentToolWorkerExecutorService;

  @Qualifier("agentRuntimeScheduledExecutorService")
  private final ScheduledExecutorService agentRuntimeScheduledExecutorService;

  Agent load(String runId, String sessionId, AgentEventHandler eventHandler) {
    EmbeddedAgentRuntimeReferenceResolver.RuntimeReferences references =
        runtimeReferenceResolver.resolve(sessionId);
    AgentSession session = references.session();
    AgentDefinition agentDefinition = references.agentDefinition();
    AgentProvider agentProvider = references.provider();
    AgentModel agentModel = references.model();
    AgentInfo agentInfo =
        runtimeMetadataFactory.toAgentInfo(
            agentDefinition, agentProvider.getName(), agentModel.getName());
    ModelInfo modelInfo = runtimeMetadataFactory.toModelInfo(agentModel, agentProvider.getName());
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

    AgentRegistry agentRegistry = new SingleAgentRegistry(agentInfo);
    ModelRegistry modelRegistry = new SingleModelRegistry(modelInfo);
    ProviderRegistry providerRegistry = new SingleProviderRegistry(agentProvider.getName(), providerInfo);
    AgentRuntimeConfigResolver runtimeConfigResolver = new AgentRuntimeConfigResolver(
        agentRegistry, modelRegistry, providerRegistry, providerManager, toolRegistry);

    AgentFactory.Dependencies deps = new AgentFactory.Dependencies(
        toolRegistry, toolCallExecutor, sessionManager,
        new CoreSessionEventMessageProjector(), eventHandler, runtimeConfigResolver);

    return agentFactory.load(
        sessionId,
        agentDefinition.getName(),
        agentProvider.getName(),
        agentModel.getName(),
        agentInfo.getDefaultVariant(),
        userRequestQueue,
        agentScheduler,
        ModelRetryConfig.builder()
            .maxRetries(0)
            .baseDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(2D)
            .build(),
        deps);
  }
}
