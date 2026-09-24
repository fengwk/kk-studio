package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.platform.project.controller.IssueControllerProperties;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconciler;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Issue Controller 运行时装配：drain executor、worker executor、poll scheduler、dispatcher 与 SmartLifecycle。
 *
 * <p>进程内 dispatcher 受 {@code HarnessRuntimeProperties.isWorkersEnabled()} 控制； false
 * 时不启动调度。PostgreSQL notification loop 为独立应用级生命周期，不受 worker 开关影响。
 *
 * <p>所有 executor 线程均为 daemon 并以 {@code destroyMethod = "shutdown"} 交给 Spring 持有生命周期； dispatcher 使用
 * fail-fast 单线程 drain executor 与 bounded AbortPolicy worker executor。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IssueControllerProperties.class, HarnessRuntimeProperties.class})
public class IssueControllerRuntimeConfiguration {

  /** fail-fast 单线程 drain executor：串行执行 drain，拒绝时同步抛错。 */
  @Bean(name = "issueControllerDispatcherDrainExecutor", destroyMethod = "shutdown")
  public ExecutorService issueControllerDispatcherDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("issue-dispatch-drain-", 0L).daemon(true).factory());
  }

  /** bounded worker executor：固定并发 + 有界队列 + AbortPolicy（fail-fast，绝不静默丢弃 handoff task）。 */
  @Bean(name = "issueControllerDispatcherWorkerExecutor", destroyMethod = "shutdown")
  public ExecutorService issueControllerDispatcherWorkerExecutor(
      IssueControllerProperties properties) {
    int concurrency = properties.getWorker().getConcurrency();
    int queueCapacity = properties.getWorker().getQueueCapacity();
    return new ThreadPoolExecutor(
        concurrency,
        concurrency,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(queueCapacity),
        Thread.ofPlatform().name("issue-dispatch-worker-", 0L).daemon(true).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean(name = "issueControllerDispatcherPollScheduler", destroyMethod = "shutdown")
  public ScheduledExecutorService issueControllerDispatcherPollScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("issue-dispatch-poll-", 0L).daemon(true).factory());
  }

  @Bean
  public IssueControllerDispatcher issueControllerDispatcher(
      IssueWorkStore workStore,
      IssueReconciler reconciler,
      IssueControllerProperties properties,
      Clock clock,
      @Qualifier("issueControllerDispatcherDrainExecutor") Executor drainExecutor,
      @Qualifier("issueControllerDispatcherWorkerExecutor") Executor workerExecutor,
      @Qualifier("issueControllerDispatcherPollScheduler") ScheduledExecutorService pollScheduler) {
    return new IssueControllerDispatcher(
        workStore, reconciler, properties, clock, drainExecutor, workerExecutor, pollScheduler);
  }

  @Bean
  public SmartLifecycle issueControllerRuntimeLifecycle(
      HarnessRuntimeProperties properties, IssueControllerDispatcher dispatcher) {
    return new IssueControllerRuntimeLifecycle(properties.isWorkersEnabled(), dispatcher);
  }
}
