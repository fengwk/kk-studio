package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Canvas Function 有界进程内 worker executor 装配。
 *
 * <p>executor 是长生命周期部署拓扑，只读取 bootstrap properties。
 */
@Configuration(proxyBeanMethods = false)
public class CanvasFunctionRuntimeConfiguration {

  @Bean(name = "canvasFunctionExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionExecutor(CanvasFunctionRuntimeProperties properties) {
    properties.validate();
    return new ThreadPoolExecutor(
        properties.getCoreSize(),
        properties.getMaxSize(),
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(properties.getQueueCapacity()),
        Thread.ofPlatform().name("canvas-function-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public CanvasFunctionDispatcher canvasFunctionDispatcher(
      @Qualifier("canvasFunctionExecutor") ExecutorService executor, CanvasFunctionWorker worker) {
    return new CanvasFunctionDispatcher(executor, worker);
  }
}
