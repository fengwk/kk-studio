package fun.fengwk.kkstudio.platform.harness.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/**
 * Platform 侧的 Harness 自动对话压缩配置现读通道：每次决策点从 {@link SystemSettingsSnapshot} 的 {@code
 * aiRuntime.compactionKeepRecentTokens} 映射 {@link CompactionConfig}。
 *
 * <p>{@link CompactionConfigProvider} 同时被 platform 的 {@code DatabaseTurnResolver} 与 web 组合根的 {@code
 * ThreadProcessorConfig} 消费，作为 platform 的唯一定义点。aiRuntime 配置 live 生效，无需重启。
 */
@Configuration(proxyBeanMethods = false)
public class HarnessCompactionConfiguration {

  /** 自动对话压缩配置：保留最近上下文 token 上限；每次决策点现读。 */
  @Bean
  public CompactionConfigProvider compactionConfigProvider(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return () -> {
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      return new CompactionConfig(aiRuntime.compactionKeepRecentTokens());
    };
  }
}
