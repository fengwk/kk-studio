package fun.fengwk.kkstudio.testconfiguration;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.core.harness.redis.RedisExecutionTargetDispatcher;

/** Supplies the dispatcher mock before production Redis wiring is evaluated. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisActivationDispatcherTestConfiguration {

  @Bean
  RedisExecutionTargetDispatcher redisExecutionTargetDispatcher() {
    return mock(RedisExecutionTargetDispatcher.class);
  }
}
