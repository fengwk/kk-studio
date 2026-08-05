package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.Executor;

/**
 * Test transport executor overrides for the shared web context.
 *
 * <p>Force Harness SSE polling onto the calling thread so MockMvc async dispatch observes events
 * deterministically. The Harness Runtime composition root ({@code web.runtime}) provides the real
 * beans; {@code workers-enabled=false} keeps the control/query plane available without starting the
 * worker dispatcher/listener.
 */
@Configuration
public class HarnessWebTestConfiguration {

  @Bean(name = "harnessEventStreamTaskExecutor")
  @Primary
  public Executor harnessEventStreamTaskExecutor() {
    return Runnable::run;
  }
}
