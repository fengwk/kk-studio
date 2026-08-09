package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

/**
 * Core 唯一 CompactionConfig bean 定义：由 HarnessRuntimeProperties 绑定驱动，可被 core resolver 与 web 组合根共享。
 */
class HarnessCompactionConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(HarnessCompactionConfiguration.class);

  @Test
  void providesCompactionConfigBeanFromDefaultProperties() {
    contextRunner.run(
        context -> {
          CompactionConfig config = context.getBean(CompactionConfig.class);
          assertEquals(true, config.enabled());
          assertEquals(16_384, config.reserveTokens());
          assertEquals(20_000, config.maxRecentTokens());
        });
  }

  @Test
  void compactionConfigBeanFollowsPropertyOverrides() {
    contextRunner
        .withPropertyValues(
            "kk-studio.harness.runtime.compaction-enabled=false",
            "kk-studio.harness.runtime.compaction-reserve-tokens=4096",
            "kk-studio.harness.runtime.compaction-max-recent-tokens=8192")
        .run(
            context -> {
              CompactionConfig config = context.getBean(CompactionConfig.class);
              assertEquals(false, config.enabled());
              assertEquals(4_096, config.reserveTokens());
              assertEquals(8_192, config.maxRecentTokens());
            });
  }
}
