package fun.fengwk.kkstudio.platform.ai.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;

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
              assertNull(config.fallbackModel());
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
              assertNull(config.fallbackModel());
            });
  }

  @Test
  void fallbackModelFlowsFromSystemSettingsToCompactionConfig() {
    // 非 null fallback 必须完整贯通：SystemSettings.AiRuntime -> SystemSettingsSnapshot
    // -> HarnessCompactionConfiguration -> CompactionConfig.fallbackModel。
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            SystemSettings.AiRuntime.DEFAULT.retryMaxRetries(),
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            4_096,
            new ModelSelection("minimax", "MiniMax-Text-01", "pro"),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
            SystemSettings.AiRuntime.DEFAULT.subagentIdleTimeoutMillis(),
            SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns());
    new ApplicationContextRunner()
        .withUserConfiguration(HarnessCompactionConfiguration.class)
        .withBean(
            SystemSettingsSnapshot.class,
            () ->
                new SystemSettingsSnapshot(
                    new SystemSettings(
                        SystemSettings.Tool.DEFAULT,
                        aiRuntime,
                        SystemSettings.Environment.DEFAULT,
                        SystemSettings.Integrations.DEFAULT,
                        SystemSettings.StorageMedia.DEFAULT,
                        SystemSettings.Advanced.DEFAULT)))
        .run(
            context -> {
              CompactionConfig config =
                  context.getBean(CompactionConfigProvider.class).compactionConfig();
              assertEquals(4_096, config.keepRecentTokens());
              assertNotNull(config.fallbackModel());
              assertEquals("minimax", config.fallbackModel().providerName());
              assertEquals("MiniMax-Text-01", config.fallbackModel().modelName());
              assertEquals("pro", config.fallbackModel().variant());
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
            null,
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
