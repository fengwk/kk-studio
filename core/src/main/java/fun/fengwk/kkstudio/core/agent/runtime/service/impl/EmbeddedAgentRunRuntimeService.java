package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequest;
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
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.Agent;
import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.AgentFactory;
import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.AgentStatus;
import fun.fengwk.kkstudio.agent.ModelRetryConfig;
import fun.fengwk.kkstudio.agent.UserRequest;
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
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
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

  private static final String DEFAULT_VARIANT = "default";
  private static final Duration RUN_IDLE_TIMEOUT = Duration.ofMinutes(3);
  private static final long RUN_IDLE_POLL_MILLIS = 50L;

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
  private final EmbeddedAgentRuntimeMetadataFactory runtimeMetadataFactory;

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
      AgentSession session = requireSession(sessionId);
      AgentDefinition agentDefinition = requireAgentDefinition(session.getAgentId());
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
        firstNonBlank(agentDefinition.getDefaultVariant(), DEFAULT_VARIANT),
        userRequestQueue,
        agentEventHandler,
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

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
