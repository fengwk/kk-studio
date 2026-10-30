package fun.fengwk.kkstudio.canvas.infra.function;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkDispatcherLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Canvas Function durable dispatcher 的固定并发 executor 装配。
 *
 * <p>executor 是长生命周期部署拓扑，只读取 bootstrap properties。
 */
@Configuration(proxyBeanMethods = false)
public class CanvasFunctionRuntimeConfiguration {

  private static final String DISPATCHER_NAME = "canvas function dispatcher";

  @Bean(name = "canvasFunctionDrainExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("canvas-function-drain").daemon(true).factory());
  }

  @Bean(name = "canvasFunctionWorkerExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionWorkerExecutor(
      CanvasFunctionRuntimeProperties properties, ContextSnapshotFactory contextSnapshotFactory) {
    properties.validate();
    return ContextExecutorService.wrap(
        new ThreadPoolExecutor(
            properties.getWorkerConcurrency(),
            properties.getWorkerConcurrency(),
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            Thread.ofPlatform().name("canvas-function-worker-", 0L).daemon(true).factory(),
            new ThreadPoolExecutor.AbortPolicy()),
        contextSnapshotFactory);
  }

  @Bean(name = "canvasFunctionPollScheduler", destroyMethod = "shutdownNow")
  public ScheduledExecutorService canvasFunctionPollScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("canvas-function-poll").daemon(true).factory());
  }

  @Bean(name = "canvasFunctionHeartbeatScheduler", destroyMethod = "shutdownNow")
  public ScheduledExecutorService canvasFunctionHeartbeatScheduler(
      CanvasFunctionRuntimeProperties properties) {
    properties.validate();
    return Executors.newScheduledThreadPool(
        properties.getWorkerConcurrency(),
        Thread.ofPlatform().name("canvas-function-heartbeat-", 0L).daemon(true).factory());
  }

  @Bean(name = "canvasFunctionDispatcher")
  public BoundedWorkDispatcher<ClaimedRun> canvasFunctionDispatcher(
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      @Qualifier("canvasFunctionDrainExecutor") Executor drainExecutor,
      @Qualifier("canvasFunctionWorkerExecutor") Executor workerExecutor,
      @Qualifier("canvasFunctionPollScheduler") ScheduledExecutorService pollScheduler,
      CanvasFunctionWorker worker) {
    properties.validate();
    CanvasFunctionWorkStore store = Objects.requireNonNull(workStore, "workStore");
    CanvasFunctionWorker functionWorker = Objects.requireNonNull(worker, "worker");
    return new BoundedWorkDispatcher<>(
        DISPATCHER_NAME,
        properties.getMaxDispatchTasks(),
        Duration.ofMillis(properties.getPollIntervalMillis()),
        drainExecutor,
        workerExecutor,
        pollScheduler,
        () -> claimNext(store, properties),
        functionWorker::run,
        claim -> returnClaim(store, properties, claim));
  }

  /** Canvas Function 调度始终启用，独立于 Harness worker 开关。 */
  @Bean(name = "canvasFunctionDispatcherLifecycle")
  public SmartLifecycle canvasFunctionDispatcherLifecycle(
      @Qualifier("canvasFunctionDispatcher") BoundedWorkDispatcher<ClaimedRun> dispatcher) {
    return new WorkDispatcherLifecycle(true, dispatcher);
  }

  /** Function Run 的调度邮箱：{@code durable_work} 中 target type 为 CANVAS_RUN 的行。 */
  @Bean
  public DurableWorkStore canvasRunWorkQueue(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    return new DurableWorkStore(
        CanvasFunctionWorkStore.TARGET_TYPE, jdbcClient, transactionManager);
  }

  private static ClaimedRun claimNext(
      CanvasFunctionWorkStore workStore, CanvasFunctionRuntimeProperties properties) {
    return workStore
        .claimNext(
            Duration.ofMillis(properties.getLeaseDurationMillis()), UUID.randomUUID().toString())
        .orElse(null);
  }

  /** ownership-fenced 归还：只把仍持有的 RUNNING claim 按 rejectionDelay 重排回 READY。 */
  private static void returnClaim(
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      ClaimedRun claim) {
    workStore.reschedule(claim, Duration.ofMillis(properties.getRejectionDelayMillis()));
  }
}
