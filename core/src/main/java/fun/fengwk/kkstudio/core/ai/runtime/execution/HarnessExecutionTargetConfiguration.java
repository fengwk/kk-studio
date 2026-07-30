package fun.fengwk.kkstudio.core.ai.runtime.execution;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Production composition for the sole durable Harness activation path.
 *
 * <p>The PostgreSQL listener only wakes the single target dispatcher. The dispatcher obtains due
 * rows from {@code harness_execution_target}, filters ENVIRONMENT routes by the local READY
 * snapshot, then delegates each row to the Runtime entry point that re-locks the durable facts.
 * Redis is intentionally absent from this composition.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessExecutionTargetConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ExecutionTargetRouteEligibility executionTargetRouteEligibility(
      LiveEnvironmentRegistry environmentRegistry) {
    return () ->
        environmentRegistry.listReady().stream()
            .map(LiveEnvironment::environmentName)
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Breaks the Gateway -> ToolWorker -> dispatcher construction cycle with a lazy dispatcher
   * lookup. READY only wakes a durable scan; route eligibility is read from the registry at drain
   * time.
   */
  @Bean
  @ConditionalOnMissingBean
  public EnvironmentReadyListener environmentReadyListener(
      ObjectProvider<PostgresqlExecutionTargetDispatcher> dispatcherProvider) {
    return ignored -> dispatcherProvider.ifAvailable(PostgresqlExecutionTargetDispatcher::wake);
  }

  @Bean(name = "executionTargetDrainExecutor", destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ExecutorService executionTargetDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("execution-target-drain-", 0L).daemon(true).factory());
  }

  @Bean(name = "executionTargetWakeExecutor", destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ScheduledExecutorService executionTargetWakeExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("execution-target-wake-", 0L).daemon(true).factory());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ExecutionTargetHandler executionTargetHandler(
      ThreadReconciler threadReconciler, ModelWorker modelWorker, ToolWorker toolWorker) {
    return new HarnessExecutionTargetHandler(threadReconciler, modelWorker, toolWorker);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PostgresqlExecutionTargetDispatcher postgresqlExecutionTargetDispatcher(
      ExecutionTargetStore executionTargetStore,
      ExecutionTargetRouteEligibility routeEligibility,
      ExecutionTargetHandler executionTargetHandler,
      @Qualifier("executionTargetDrainExecutor") ExecutorService executionTargetDrainExecutor,
      @Qualifier("executionTargetWakeExecutor")
          ScheduledExecutorService executionTargetWakeExecutor) {
    return new PostgresqlExecutionTargetDispatcher(
        executionTargetStore,
        routeEligibility,
        executionTargetHandler,
        Clock.systemUTC(),
        executionTargetDrainExecutor,
        executionTargetWakeExecutor);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PostgresqlExecutionTargetListener postgresqlExecutionTargetListener(
      DataSource dataSource, PostgresqlExecutionTargetDispatcher dispatcher) {
    return new PostgresqlExecutionTargetListener(dataSource, dispatcher);
  }

  /**
   * Starts durable activation only after the application context has initialized its data sources
   * and worker composition. This avoids binding LISTEN to a transient datasource during startup hot
   * replacement.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public SmartLifecycle harnessExecutionTargetLifecycle(
      PostgresqlExecutionTargetDispatcher dispatcher, PostgresqlExecutionTargetListener listener) {
    return new ExecutionTargetLifecycle(dispatcher, listener);
  }

  private static final class ExecutionTargetLifecycle implements SmartLifecycle {
    private final PostgresqlExecutionTargetDispatcher dispatcher;
    private final PostgresqlExecutionTargetListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutionTargetLifecycle(
        PostgresqlExecutionTargetDispatcher dispatcher,
        PostgresqlExecutionTargetListener listener) {
      this.dispatcher = dispatcher;
      this.listener = listener;
    }

    @Override
    public void start() {
      if (!running.compareAndSet(false, true)) {
        return;
      }
      try {
        dispatcher.start();
        listener.start();
      } catch (RuntimeException failure) {
        listener.stop();
        dispatcher.stop();
        running.set(false);
        throw failure;
      }
    }

    @Override
    public void stop() {
      if (!running.compareAndSet(true, false)) {
        return;
      }
      listener.stop();
      dispatcher.stop();
    }

    @Override
    public void stop(Runnable callback) {
      try {
        stop();
      } finally {
        callback.run();
      }
    }

    @Override
    public boolean isRunning() {
      return running.get();
    }

    @Override
    public boolean isAutoStartup() {
      return true;
    }

    @Override
    public int getPhase() {
      return Integer.MAX_VALUE;
    }
  }
}
