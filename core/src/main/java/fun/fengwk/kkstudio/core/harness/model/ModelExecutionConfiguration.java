package fun.fengwk.kkstudio.core.harness.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring wiring for the production {@code ModelExecution} adapter.
 *
 * <p>Provider I/O runs on Java 21 virtual threads. The executor is an infrastructure resource owned
 * by Spring and closes with the application context; it carries blocking Provider calls only, never
 * durable actor continuations.
 */
@Configuration(proxyBeanMethods = false)
public class ModelExecutionConfiguration {

  /**
   * Virtual-thread executor for blocking Provider I/O. A composition root may replace this named
   * bean, but any replacement must preserve the same blocking-I/O-only boundary.
   */
  @Bean(name = "modelExecutionExecutor", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "modelExecutionExecutor")
  public ExecutorService modelExecutionExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("model-provider-", 0L).factory());
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock modelExecutionClock() {
    return Clock.systemUTC();
  }
}
