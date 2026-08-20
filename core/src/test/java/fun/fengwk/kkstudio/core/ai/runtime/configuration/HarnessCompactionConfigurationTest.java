package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

/**
 * Core 唯一 CompactionConfig bean 定义：由共享启动快照 SystemSettings.AiRuntime 的保留上下文配置驱动，可被 core resolver 与
 * web 组合根共享。
 */
class HarnessCompactionConfigurationTest {

  @Test
  void providesCompactionConfigBeanFromSystemSettingsDefaults() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class, () -> new SystemSettingsSnapshot(SystemSettings.DEFAULT))
        .run(
            context -> {
              CompactionConfig config = context.getBean(CompactionConfig.class);
              assertEquals(20_000, config.keepRecentTokens());
              assertNull(config.fallbackModel());
            });
  }

  @Test
  void compactionConfigBeanFollowsSystemSettingsOverrides() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(settingsWithCompaction(false, 4_096, 8_192)))
        .run(
            context -> {
              CompactionConfig config = context.getBean(CompactionConfig.class);
              assertEquals(8_192, config.keepRecentTokens());
              assertNull(config.fallbackModel());
            });
  }

  private static SystemSettings settingsWithCompaction(
      boolean enabled, int reserveTokens, int maxRecentTokens) {
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            3,
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            enabled,
            reserveTokens,
            maxRecentTokens,
            SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentIdleTimeoutMillis(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns());
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        aiRuntime,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
