package fun.fengwk.kkstudio.platform.harness.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/**
 * Platform 侧的 Harness 自动对话压缩配置现读通道：每次决策点从 {@link SystemSettingsSnapshot} 的 {@code
 * aiRuntime.compactionKeepRecentTokens} 与 {@code aiRuntime.compactionFallbackModel} 映射 {@link
 * CompactionConfig}。
 *
 * <p>{@link CompactionConfigProvider} 同时被 platform 的 {@code DatabaseTurnResolver} 与 web 组合根的 {@code
 * ThreadProcessorConfig} 消费，因此放在 platform 作为唯一定义点；web 不再重复声明。aiRuntime 配置 live 生效，无需重启。
 */
@Configuration(proxyBeanMethods = false)
public class HarnessCompactionConfiguration {

  /** 自动对话压缩配置：保留最近上下文 token 上限与可选 fallback model；每次决策点现读。 */
  @Bean
  public CompactionConfigProvider compactionConfigProvider(
      SystemSettingsSnapshot systemSettingsSnapshot) {
    return () -> {
      SystemSettings.AiRuntime aiRuntime = systemSettingsSnapshot.get().aiRuntime();
      return new CompactionConfig(
          aiRuntime.compactionKeepRecentTokens(), aiRuntime.compactionFallbackModel());
    };
  }
}
