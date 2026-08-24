package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.util.List;
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

  @Bean
  @ConditionalOnMissingBean(CanvasFunctionCatalog.class)
  public CanvasFunctionCatalog canvasFunctionCatalog(List<CanvasFunctionAdapter> adapters) {
    return CanvasFunctionCatalog.from(adapters);
  }

  @Bean(name = "canvasFunctionDrainExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionDrainExecutor() {
    return Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("canvas-function-drain").daemon(true).factory());
  }

  @Bean(name = "canvasFunctionWorkerExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionWorkerExecutor(CanvasFunctionRuntimeProperties properties) {
    properties.validate();
    return new ThreadPoolExecutor(
        properties.getWorkerConcurrency(),
        properties.getWorkerConcurrency(),
        0L,
        TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        Thread.ofPlatform().name("canvas-function-worker-", 0L).daemon(true).factory(),
        new ThreadPoolExecutor.AbortPolicy());
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
      Clock clock,
      @Qualifier("canvasFunctionDrainExecutor") Executor drainExecutor,
      @Qualifier("canvasFunctionWorkerExecutor") Executor workerExecutor,
      @Qualifier("canvasFunctionPollScheduler") ScheduledExecutorService pollScheduler,
      CanvasFunctionWorker worker) {
    return new CanvasFunctionDispatcher(
        workStore, properties, clock, drainExecutor, workerExecutor, pollScheduler, worker);
  }
}
