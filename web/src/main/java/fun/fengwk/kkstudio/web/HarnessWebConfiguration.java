package fun.fengwk.kkstudio.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Harness SSE 适配器使用的 Web 侧传输 executor。 */
@Configuration
public class HarnessWebConfiguration {

  /** 单进程内并发 DB 轮询 SSE 循环的上限。 */
  static final int EVENT_STREAM_MAX_CONCURRENT = 32;

  @Bean(name = "harnessEventStreamTaskExecutor")
  @ConditionalOnMissingBean(name = "harnessEventStreamTaskExecutor")
  public ThreadPoolTaskExecutor harnessEventStreamTaskExecutor() {
    // 有界且无队列的线程池：过载时立即拒绝，而不是打开一个永远拿不到 worker 线程的空闲 SSE 连接。
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("harness-event-stream-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(EVENT_STREAM_MAX_CONCURRENT);
    executor.setQueueCapacity(0);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }
}
