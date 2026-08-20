package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

/**
 * Core 侧的 Harness 自动对话压缩配置：从共享启动快照 {@link SystemSettingsSnapshot} 的 {@code
 * aiRuntime.compactionMaxRecentTokens} 提供保留上下文的配置事实。
 *
 * <p>{@link CompactionConfig} 同时被 core 的 {@code DatabaseTurnResolver} 与 web 组合根的 {@code
 * ThreadProcessorConfig} 消费，因此放在 core 作为唯一定义点；web 不再重复声明。快照在装配期一次 DB 读取（DB 变更需重启生效）。
 */
@Configuration(proxyBeanMethods = false)
public class HarnessCompactionConfiguration {

  /** 自动对话压缩配置：保留的最近上下文 token 上限；fallback model 由运行时配置单独提供。 */
  @Bean
  public CompactionConfig compactionConfig(SystemSettingsSnapshot systemSettingsSnapshot) {
    SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
    return new CompactionConfig(aiRuntime.compactionMaxRecentTokens(), null);
  }
}
