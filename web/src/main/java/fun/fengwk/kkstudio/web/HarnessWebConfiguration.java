package fun.fengwk.kkstudio.web;

import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** Web-side transport executors used by Harness SSE adapters. */
@Configuration
public class HarnessWebConfiguration {

  @Bean(name = "harnessEventStreamTaskExecutor")
  @ConditionalOnMissingBean(name = "harnessEventStreamTaskExecutor")
  public Executor harnessEventStreamTaskExecutor() {
    return new SimpleAsyncTaskExecutor("harness-event-stream-");
  }
}
