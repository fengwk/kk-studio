package fun.fengwk.kkstudio.web;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
