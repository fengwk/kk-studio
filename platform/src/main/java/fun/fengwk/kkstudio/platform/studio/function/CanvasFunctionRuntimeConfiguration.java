package fun.fengwk.kkstudio.platform.studio.function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Canvas Function 有界进程内 worker executor 装配。
 *
 * <p>executor 是长生命周期拓扑：规模来自启动时 SystemSettings.advanced 快照，运行时更新需重启生效。
 */
@Configuration(proxyBeanMethods = false)
public class CanvasFunctionRuntimeConfiguration {

  @Bean(name = "canvasFunctionExecutor", destroyMethod = "shutdownNow")
  public ExecutorService canvasFunctionExecutor(SystemSettingsSnapshot snapshot) {
    var advanced = snapshot.get().advanced();
    return new ThreadPoolExecutor(
        advanced.canvasFunctionExecutorCoreSize(),
        advanced.canvasFunctionExecutorMaxSize(),
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(advanced.canvasFunctionExecutorQueueCapacity()),
        Thread.ofPlatform().name("canvas-function-", 0L).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public CanvasFunctionDispatcher canvasFunctionDispatcher(
      @Qualifier("canvasFunctionExecutor") ExecutorService executor, CanvasFunctionWorker worker) {
    return new CanvasFunctionDispatcher(executor, worker);
  }
}
