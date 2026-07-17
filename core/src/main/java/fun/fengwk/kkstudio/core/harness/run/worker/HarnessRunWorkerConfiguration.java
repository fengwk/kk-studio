package fun.fengwk.kkstudio.core.harness.run.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.run.resource.DatabaseTurnResourceResolver;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.run.DeltaFlushScheduler;
import fun.fengwk.kkstudio.harness.runtime.run.InterceptingCompactionService;
import fun.fengwk.kkstudio.harness.runtime.run.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.run.RunWorkerConfig;
import fun.fengwk.kkstudio.harness.runtime.run.ToolPreparationPort;
import fun.fengwk.kkstudio.harness.runtime.run.TurnResourceResolver;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 当 Provider/Model 与 Compaction 端口就绪时装配数据库驱动的 Agent Turn Worker。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessRunWorkerConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public RunWorkerConfig harnessRunWorkerConfig() {
    return RunWorkerConfig.DEFAULT;
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock harnessRunClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public DeltaFlushScheduler deltaFlushScheduler() {
    return (delay, task) ->
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task);
  }

  @Bean(name = "harnessWorkerScheduler", destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "harnessWorkerScheduler")
  public ScheduledExecutorService harnessWorkerScheduler() {
    return Executors.newScheduledThreadPool(2);
  }

  @Bean
  @ConditionalOnMissingBean
  public TurnResourceResolver turnResourceResolver(
      MysqlHarnessSessionStore sessionStore,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentModelRuntimeConfigParser modelConfigParser,
      HarnessExtensionHost host,
      HarnessRuntimeProperties properties,
      ToolEnvironmentRepository environmentRepository,
      DaemonToolCapabilitiesCodec capabilitiesCodec,
      ObjectMapper objectMapper) {
    return new DatabaseTurnResourceResolver(
        sessionStore,
        sessionStore,
        modelRepository,
        providerRepository,
        modelConfigParser,
        host,
        properties,
        environmentRepository,
        capabilitiesCodec,
        objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public CompactionService compactionService() {
    return (sessionId, context) -> Optional.empty();
  }

  @Bean
  @ConditionalOnBean({TurnResourceResolver.class, CompactionService.class})
  @ConditionalOnMissingBean
  public AgentTurnWorker agentTurnWorker(
      MysqlHarnessRunStore runStore,
      HarnessRunTransactionService transactions,
      ToolPreparationPort toolPreparationPort,
      MysqlHarnessSessionStore sessionStore,
      HarnessExtensionHost host,
      HarnessLifecycleObservers lifecycleObservers,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      RunWorkerConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler) {
    SessionContextBuilder contextBuilder =
        new SessionContextBuilder(
            sessionStore, sessionStore, new DefaultContextTransform(), host.contextTransforms());
    CompactionService interceptingCompactionService =
        new InterceptingCompactionService(compactionService, host.beforeCompactionInterceptors());
    return new AgentTurnWorker(
        runStore,
        runStore,
        transactions,
        toolPreparationPort,
        contextBuilder,
        new ProviderMessageProjector(),
        resourceResolver,
        interceptingCompactionService,
        config,
        clock,
        deltaFlushScheduler,
        new ProviderRequestInterceptorChain(host.beforeProviderRequestInterceptors()),
        lifecycleObservers);
  }

  @Bean
  @ConditionalOnMissingBean
  public AgentTurnWorkerLifecycle agentTurnWorkerLifecycle(
      AgentTurnWorker worker,
      RunWorkerConfig config,
      HarnessRuntimeProperties properties,
      @Qualifier("harnessWorkerScheduler") ScheduledExecutorService scheduler) {
    return new AgentTurnWorkerLifecycle(worker, config, properties, scheduler);
  }
}
