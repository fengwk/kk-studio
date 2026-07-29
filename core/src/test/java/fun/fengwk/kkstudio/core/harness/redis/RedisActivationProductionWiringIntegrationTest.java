package fun.fengwk.kkstudio.core.harness.redis;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static fun.fengwk.kkstudio.core.harness.redis.RedisRuntimeSupport.REDIS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.testconfiguration.RedisActivationDispatcherTestConfiguration;

/**
 * Verifies production Spring wiring from Redis publish through the configured listener container.
 */
@SpringBootTest(
    classes = {RedisActivationDispatcherTestConfiguration.class, CoreTestApplication.class},
    properties = {"kk-studio.harness.runtime.workers-enabled=true", "spring.sql.init.mode=never"})
class RedisActivationProductionWiringIntegrationTest {

  @Autowired private ActivationNotifier notifier;
  @Autowired private RedisExecutionTargetDispatcher dispatcher;
  @Autowired private RedisMessageListenerContainer harnessActivationRedisMessageListenerContainer;

  @DynamicPropertySource
  static void overrideRuntimeInfrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
  }

  @Test
  void notifierPublishesToProductionSubscriberContainer() {
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 12345L);

    assertTrue(harnessActivationRedisMessageListenerContainer.isListening());
    notifier.notifyAfterCommit(target);

    verify(dispatcher, timeout(5_000)).dispatch(target);
  }
}
