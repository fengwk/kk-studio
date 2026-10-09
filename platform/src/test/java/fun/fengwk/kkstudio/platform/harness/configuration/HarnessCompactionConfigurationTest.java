package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/**
 * Platform 唯一 CompactionConfigProvider bean 定义：由共享快照 SystemSettings.AiRuntime 的保留上下文配置驱动，可被
 * platform resolver 与 web 组合根共享；每次决策点现读。
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
              CompactionConfigProvider provider = context.getBean(CompactionConfigProvider.class);
              CompactionConfig config = provider.compactionConfig();
              assertEquals(20_000, config.keepRecentTokens());
            });
  }

  @Test
  void compactionConfigBeanFollowsSystemSettingsOverrides() {
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () -> new SystemSettingsSnapshot(settingsWithCompaction(8_192)))
        .run(
            context -> {
              CompactionConfig config =
                  context.getBean(CompactionConfigProvider.class).compactionConfig();
              assertEquals(8_192, config.keepRecentTokens());
            });
  }

  private static SystemSettings settingsWithCompaction(int keepRecentTokens) {
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            3,
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            keepRecentTokens,
            SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns());
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        aiRuntime,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Network.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
