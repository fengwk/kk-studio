package fun.fengwk.kkstudio.core.ai.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentConfig;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;

import java.time.Duration;

/**
 * 验证 {@link RuntimeToolsConfiguration#subagentConfig} 把 {@code SystemSettings.AiRuntime} 的五个
 * subagent 字段 完整映射为 {@link SubagentConfig}。
 *
 * <p>五个字段全部取与默认不同的非默认值（depth=4 / per-parent=7 / total=13 / idle=1234ms / turns=89），逐项精确断言， 防止 total
 * 与 per-parent 在 SystemSettings -&gt; runtime bean mapping 中丢失或互换。纯 JUnit 单元测试：不启动
 * Spring/Postgres，直接构造 {@code new RuntimeToolsConfiguration().subagentConfig(...)}。
 */
class RuntimeToolsConfigurationTest {

  @Test
  void subagentConfigMapsAllFiveAiRuntimeFields() {
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
            .subagentConfig(new SystemSettingsSnapshot(customSettings(aiRuntime)));

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
