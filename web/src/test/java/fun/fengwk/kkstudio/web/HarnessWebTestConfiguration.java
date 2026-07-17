package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.Executor;

/**
 * Test transport executors. Force Harness SSE polling onto the calling thread so MockMvc async
 * dispatch observes events deterministically.
 */
@Configuration
public class HarnessWebTestConfiguration {

  @Bean(name = "harnessEventStreamTaskExecutor")
  @Primary
  public Executor harnessEventStreamTaskExecutor() {
    return Runnable::run;
  }
}
