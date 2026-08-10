package fun.fengwk.kkstudio.core.studio.function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Canvas Function bounded worker executor 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CanvasFunctionExecutorProperties.class)
public class CanvasFunctionRuntimeConfiguration {

  @Bean(name = "canvasFunctionExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionExecutor(CanvasFunctionExecutorProperties properties) {
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
