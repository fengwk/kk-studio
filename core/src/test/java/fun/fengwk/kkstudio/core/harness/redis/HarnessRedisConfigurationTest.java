package fun.fengwk.kkstudio.core.harness.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import fun.fengwk.kkstudio.core.harness.realtime.stream.HarnessRealtimeStreamPolicyService;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.concurrent.Executor;

/**
 * Verifies the activation subscriber defaults to enabled but remains off in disabled test contexts.
 */
class HarnessRedisConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
          .withBean(ThreadKick.class, () -> mock(ThreadKick.class))
          .withBean(ModelWorker.class, () -> mock(ModelWorker.class))
          .withBean("modelWorkerScheduler", Executor.class, () -> (Executor) Runnable::run)
          .withBean(ToolWorker.class, () -> mock(ToolWorker.class))
          .withBean("toolWorkerScheduler", Executor.class, () -> (Executor) Runnable::run)
          .withBean(
              HarnessRealtimeStreamPolicyService.class,
              () -> mock(HarnessRealtimeStreamPolicyService.class))
          .withBean(
              "lifecycleProcessor", LifecycleProcessor.class, () -> mock(LifecycleProcessor.class))
          .withUserConfiguration(HarnessRedisConfiguration.class);

  @Test
  void registersActivationSubscriberWhenWorkersPropertyIsAbsent() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(RedisExecutionTargetSubscriber.class);
          assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);
        });
  }

  @Test
  void doesNotRegisterActivationSubscriberWhenWorkersAreDisabled() {
    contextRunner
        .withPropertyValues("kk-studio.harness.runtime.workers-enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(RedisExecutionTargetSubscriber.class);
              assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
            });
  }
}
