package fun.fengwk.kkstudio.core.harness.run.worker;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.harness.runtime.context.ContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.DefaultContextTransform;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.run.AgentTurnWorker;
import fun.fengwk.kkstudio.harness.runtime.run.CompactionService;
import fun.fengwk.kkstudio.harness.runtime.run.DeltaFlushScheduler;
import fun.fengwk.kkstudio.harness.runtime.run.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.run.RunWorkerConfig;
import fun.fengwk.kkstudio.harness.runtime.run.ToolPreparationPort;
import fun.fengwk.kkstudio.harness.runtime.run.TurnResourceResolver;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当 Provider/Model 与 Compaction 端口就绪时装配数据库驱动的 Agent Turn Worker。 */
@Configuration(proxyBeanMethods = false)
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

  @Bean
  @ConditionalOnBean({TurnResourceResolver.class, CompactionService.class})
  @ConditionalOnMissingBean
  public AgentTurnWorker agentTurnWorker(
      MysqlHarnessRunStore runStore,
      HarnessRunTransactionService transactions,
      ToolPreparationPort toolPreparationPort,
      MysqlHarnessSessionStore sessionStore,
      List<ContextTransform> contextTransforms,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      RunWorkerConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler) {
    SessionContextBuilder contextBuilder =
        new SessionContextBuilder(
            sessionStore, sessionStore, new DefaultContextTransform(), contextTransforms);
    return new AgentTurnWorker(
        runStore,
        runStore,
        transactions,
        toolPreparationPort,
        contextBuilder,
        new ProviderMessageProjector(),
        resourceResolver,
        compactionService,
        config,
        clock,
        deltaFlushScheduler);
  }
}
