package fun.fengwk.kkstudio.core.ai.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfig;

import java.time.Duration;

/**
 * 验证 {@link RuntimeToolsConfiguration#subagentConfig} 把 {@code SystemSettings.AiRuntime} 的五个
 * subagent 字段完整映射为 {@link SubagentConfig}。纯 JUnit 单元测试：不启动 Spring/Postgres，直接构造 {@code new
 * RuntimeToolsConfiguration().subagentConfig(...)}。
 */
class RuntimeToolsConfigurationTest {

  @Test
  void subagentConfigMapsAllFiveAiRuntimeFields() {
    // 使用互不相同的非默认值，确保 per-parent 与 total 上限不会在装配时丢失或互换。
    SystemSettings.AiRuntime aiRuntime =
        new SystemSettings.AiRuntime(
            SystemSettings.AiRuntime.DEFAULT.retryMaxRetries(),
            SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
            SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
            SystemSettings.AiRuntime.DEFAULT.compactionKeepRecentTokens(),
            SystemSettings.AiRuntime.DEFAULT.compactionFallbackModel(),
            4,
            7,
            13,
            1234L,
            89);

    SubagentConfig config =
        new RuntimeToolsConfiguration()
            .subagentConfigProvider(new SystemSettingsSnapshot(customSettings(aiRuntime)))
            .subagentConfig();

    assertEquals(4, config.maxDepth());
    assertEquals(7, config.maxConcurrency());
    assertEquals(13, config.maxTotalConcurrency());
    assertEquals(Duration.ofMillis(1234L), config.idleTimeout());
    assertEquals(89, config.maxTurns());
  }

  private static SystemSettings customSettings(SystemSettings.AiRuntime aiRuntime) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        aiRuntime,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Integrations.DEFAULT,
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }
}
