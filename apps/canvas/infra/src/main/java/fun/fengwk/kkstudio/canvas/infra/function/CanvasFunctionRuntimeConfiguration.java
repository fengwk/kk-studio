package fun.fengwk.kkstudio.canvas.infra.function;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.work.DurableWorkStore;

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

  @Bean
  public CanvasFunctionDispatcher canvasFunctionDispatcher(
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      @Qualifier("canvasFunctionDrainExecutor") Executor drainExecutor,
      @Qualifier("canvasFunctionWorkerExecutor") Executor workerExecutor,
      @Qualifier("canvasFunctionPollScheduler") ScheduledExecutorService pollScheduler,
      CanvasFunctionWorker worker) {
    return new CanvasFunctionDispatcher(
        workStore, properties, drainExecutor, workerExecutor, pollScheduler, worker);
  }

  /** Function Run 的调度邮箱：{@code durable_work} 中 target type 为 CANVAS_RUN 的行。 */
  @Bean
  public DurableWorkStore canvasRunWorkQueue(
      JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    return new DurableWorkStore(
        CanvasFunctionDispatcher.TARGET_TYPE, jdbcClient, transactionManager);
  }
}
