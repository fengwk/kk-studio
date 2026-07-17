package fun.fengwk.kkstudio.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Web-side transport executors used by Harness SSE adapters. */
@Configuration
public class HarnessWebConfiguration {

  /** Upper bound for concurrent DB-polling SSE loops in one process. */
  static final int EVENT_STREAM_MAX_CONCURRENT = 32;

  @Bean(name = "harnessEventStreamTaskExecutor")
  @ConditionalOnMissingBean(name = "harnessEventStreamTaskExecutor")
  public ThreadPoolTaskExecutor harnessEventStreamTaskExecutor() {
    // Bounded pool with no queue: overload rejects immediately instead of opening an inert SSE
    // connection that never gets a worker thread.
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
