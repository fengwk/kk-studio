package fun.fengwk.kkstudio.web.runtime;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelStreamConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmationRetry;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderConfigurationCodec;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Processor 组合：重试与 HTTP 策略、lease/processor 配置、调度与心跳执行器、退出确认 journal，以及三个 Processor 工厂。
 *
 * <p>Advanced 软策略在装配期从共享 {@link SystemSettingsSnapshot} 读取（DB 变更需重启）；aiRuntime 的 retry 经 {@link
 * InvocationRetryPolicyProvider} 每次判定点现读。本组合不持有任何硬编码的重复默认值。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod} 交给 Spring 持有生命周期。processor 关闭与 executor
 * shutdown 由 Spring 按依赖逆序 destroy 保证。
 */
@Configuration(proxyBeanMethods = false)
public class HarnessProcessorConfiguration {

  /** Model / Tool 调用的全局重试策略现读通道：每次 retry 判定点从 SystemSettings.AiRuntime 映射。 */
  @Bean
  public InvocationRetryPolicyProvider invocationRetryPolicyProvider(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return () -> {
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      return new InvocationRetryPolicy(
          aiRuntime.retryMaxRetries(),
          aiRuntime.retryBackoffStrategy(),
          Duration.ofMillis(aiRuntime.retryBaseDelayMillis()),
          Duration.ofMillis(aiRuntime.retryMaxDelayMillis()));
    };
  }

  /** 模型 HTTP 状态策略现读通道：每次带 HTTP 状态的失败 retry 判定点按冻结 Provider 身份解析，Provider 覆盖优先于系统名单。 */
  @Bean
  public ModelHttpErrorPolicyProvider modelHttpErrorPolicyProvider(
      SystemSettingsSnapshot systemSettingsSnapshot,
      AgentProviderRepository agentProviderRepository,
      AgentProviderConfigurationCodec providerConfigurationCodec) {
    return providerName -> {
      // live 路径绝不允许 null/blank：冻结 Provider 身份来自 Invocation；未知名称才按“继承系统名单”处理。
      Objects.requireNonNull(providerName, "providerName");
      if (providerName.isBlank()) {
        throw new IllegalArgumentException("providerName must not be blank");
      }
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      List<Integer> effective = aiRuntime.modelHttpRetryStatusCodes();
      AgentProvider provider = agentProviderRepository.getByName(providerName);
      if (provider != null) {
        List<Integer> override =
            providerConfigurationCodec.readHttpRetryStatusCodes(provider.configJson());
        if (override != null) {
          effective = override;
        }
      }
      return new ModelHttpErrorPolicy(effective);
    };
  }

  /** Model / Tool processor 共用的 claim/lease 与 heartbeat 节奏。 */
  @Bean
  public ProcessorLeaseConfig processorLeaseConfig(SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ProcessorLeaseConfig(
        Duration.ofMillis(advanced.processorLeaseDurationMillis()),
        Duration.ofMillis(advanced.processorHeartbeatIntervalMillis()));
  }

  @Bean
  public ThreadProcessorConfig threadProcessorConfig(
      CompactionConfigProvider compactionConfigProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ThreadProcessorConfig(
        Duration.ofMillis(advanced.threadResolveFailureDelayMillis()), compactionConfigProvider);
  }

  @Bean
  public ModelProcessorConfig modelProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      ModelHttpErrorPolicyProvider modelHttpErrorPolicyProvider,
      SystemSettingsSnapshot systemSettingsSnapshot,
      ToolHistoryActionResolver toolHistoryActionResolver) {
    return new ModelProcessorConfig(
        leaseConfig,
        retryPolicyProvider,
        Duration.ofMillis(
            systemSettingsSnapshot.get().advanced().modelDispatchBusyFallbackDelayMillis()),
        ModelStreamConfig.DEFAULT,
        toolHistoryActionResolver,
        modelHttpErrorPolicyProvider);
  }

  @Bean
  public ToolProcessorConfig toolProcessorConfig(
      ProcessorLeaseConfig leaseConfig,
      InvocationRetryPolicyProvider retryPolicyProvider,
      SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.Advanced advanced = systemSettingsSnapshot.get().advanced();
    return new ToolProcessorConfig(
        leaseConfig,
        retryPolicyProvider,
        Duration.ofMillis(advanced.toolPreflightFailureDelayMillis()),
        Duration.ofMillis(advanced.toolDispatchBusyFallbackDelayMillis()));
  }

  /**
   * Model / Tool 共用的 lease heartbeat 定时分发调度池。
   *
   * <p>回调仅负责非阻塞状态检查与任务分派，绝不执行数据库事务或所有权取消操作。
   */
  @Bean(name = "harnessProcessorScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService harnessProcessorScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("harness-processor-timer-", 0L).daemon(true).factory());
  }

  /**
   * Model / Tool 心跳续租事务与失联取消执行器。
   *
   * <p>采用命名虚拟线程，与阻塞 Provider / Tool I/O 严格隔离，避免长耗时调用阻塞续租。
   */
  @Bean(name = "harnessHeartbeatWorkerExecutor", destroyMethod = "close")
  public ExecutorService harnessHeartbeatWorkerExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("harness-heartbeat-worker-", 0L).factory());
  }

  @Bean
  public ThreadProcessor threadProcessor(
      HarnessStore store, AgentDriver agentDriver, ThreadProcessorConfig config) {
    return new ThreadProcessor(store, agentDriver, config);
  }

  /** Model 执行线程与定时刷新任务：每个任务一个虚拟线程（执行线程在 Provider 调用期间阻塞）。 */
  @Bean(destroyMethod = "close")
  public ExecutorService harnessModelExecutionExecutor(
      ContextSnapshotFactory contextSnapshotFactory) {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(), contextSnapshotFactory);
  }

  /** 退出确认 write-ahead journal。部署目录必须是持久卷，且每个 Studio 实例独占；文件锁在进程生命周期内持有。 */
  @Bean(destroyMethod = "close")
  public ExecutionExitJournal executionExitJournal(
      @Value("${kk-studio.harness.runtime.exit-confirmation-directory}") String directory) {
    return new FileExecutionExitJournal(Path.of(directory));
  }

  @Bean
  public ExecutionExitConfirmer executionExitConfirmer(
      HarnessStore store, ExecutionExitJournal executionExitJournal, Clock clock) {
    return new ExecutionExitConfirmer(store, executionExitJournal, clock);
  }

  /** 每秒从 journal 派发一次重放；JDBC 只在 worker 线程执行，关闭时有界取消。 */
  @Bean(initMethod = "start", destroyMethod = "close")
  public ExecutionExitConfirmationRetry executionExitConfirmationRetry(
      ExecutionExitConfirmer executionExitConfirmer) {
    return new ExecutionExitConfirmationRetry(executionExitConfirmer);
  }

  @Bean(destroyMethod = "close")
  public ModelProcessor modelProcessor(
      HarnessStore store,
      ModelGateway modelGateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker,
      @Qualifier("harnessModelExecutionExecutor") ExecutorService executionExecutor,
      ExecutionExitConfirmer executionExitConfirmer) {
    return new ModelProcessor(
        store,
        modelGateway,
        realtimeEventSink,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        executionExecutor,
        executionExitConfirmer);
  }

  @Bean(destroyMethod = "close")
  public ToolProcessor toolProcessor(
      HarnessStore store,
      ToolGateway toolGateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      @Qualifier("harnessProcessorScheduler") ScheduledExecutorService scheduler,
      @Qualifier("harnessHeartbeatWorkerExecutor") Executor heartbeatWorker,
      ExecutionExitConfirmer executionExitConfirmer,
      ToolResultHistoryMaterializer materializer) {
    return new ToolProcessor(
        store,
        toolGateway,
        realtimeEventSink,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        executionExitConfirmer,
        materializer);
  }
}
