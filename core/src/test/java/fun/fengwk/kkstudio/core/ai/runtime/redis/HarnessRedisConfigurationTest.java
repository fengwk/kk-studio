package fun.fengwk.kkstudio.core.ai.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.core.ai.runtime.realtime.HarnessRealtimeEventTail;
import fun.fengwk.kkstudio.core.ai.runtime.realtime.stream.HarnessRealtimeStreamPolicyService;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

/** Redis composition exposes realtime projection only and has no activation dependencies. */
class HarnessRedisConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(
              HarnessRealtimeStreamPolicyService.class,
              () -> mock(HarnessRealtimeStreamPolicyService.class))
          .withUserConfiguration(HarnessRedisConfiguration.class);

  @Test
  void composesRealtimeAdaptersWithoutRedisConnectionOrWorkerBeans() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(RealtimeEventJsonCodec.class);
          assertThat(context).hasSingleBean(RealtimeEventSink.class);
          assertThat(context).hasSingleBean(HarnessRealtimeEventTail.class);
        });
  }
}
