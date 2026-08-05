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

/** Harness ExecutionActivation 的生产配线。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessExecutionActivationConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ExecutionActivationEnvironmentEligibility executionActivationEnvironmentEligibility(
      LiveEnvironmentRegistry environmentRegistry) {
    return () ->
        environmentRegistry.listReady().stream()
            .map(environment -> environment.id().value())
            .collect(Collectors.toUnmodifiableSet());
  }

  /** READY 事件只唤醒持久化扫描，实际 Environment 快照在分发器扫描时重新读取。 */
  @Bean
  @ConditionalOnMissingBean
  public EnvironmentReadyListener environmentReadyListener(
      ObjectProvider<PostgresqlExecutionActivationDispatcher> dispatcherProvider) {
    return ignored -> dispatcherProvider.ifAvailable(PostgresqlExecutionActivationDispatcher::wake);
  }

  @Bean(name = "executionActivationDrainExecutor", destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ExecutorService executionActivationDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("execution-activation-drain-", 0L).daemon(true).factory());
  }

  @Bean(name = "executionActivationWakeExecutor", destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ScheduledExecutorService executionActivationWakeExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("execution-activation-wake-", 0L).daemon(true).factory());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public ExecutionActivationHandler executionActivationHandler(
      ThreadReconciler threadReconciler, ModelWorker modelWorker, ToolWorker toolWorker) {
    return new HarnessExecutionActivationHandler(threadReconciler, modelWorker, toolWorker);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PostgresqlExecutionActivationDispatcher postgresqlExecutionActivationDispatcher(
      ExecutionActivationStore executionActivationStore,
      ExecutionActivationEnvironmentEligibility environmentEligibility,
      ExecutionActivationHandler executionActivationHandler,
      @Qualifier("executionActivationDrainExecutor") ExecutorService drainExecutor,
      @Qualifier("executionActivationWakeExecutor") ScheduledExecutorService wakeExecutor) {
    return new PostgresqlExecutionActivationDispatcher(
        executionActivationStore,
        environmentEligibility,
        executionActivationHandler,
        Clock.systemUTC(),
        drainExecutor,
        wakeExecutor);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public PostgresqlExecutionActivationListener postgresqlExecutionActivationListener(
      DataSource dataSource, PostgresqlExecutionActivationDispatcher dispatcher) {
    return new PostgresqlExecutionActivationListener(dataSource, dispatcher);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "kk-studio.harness.runtime",
      name = "workers-enabled",
      havingValue = "true",
      matchIfMissing = true)
  public SmartLifecycle harnessExecutionActivationLifecycle(
      PostgresqlExecutionActivationDispatcher dispatcher,
      PostgresqlExecutionActivationListener listener) {
    return new ExecutionActivationLifecycle(dispatcher, listener);
  }

  private static final class ExecutionActivationLifecycle implements SmartLifecycle {

    private final PostgresqlExecutionActivationDispatcher dispatcher;
    private final PostgresqlExecutionActivationListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutionActivationLifecycle(
        PostgresqlExecutionActivationDispatcher dispatcher,
        PostgresqlExecutionActivationListener listener) {
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
