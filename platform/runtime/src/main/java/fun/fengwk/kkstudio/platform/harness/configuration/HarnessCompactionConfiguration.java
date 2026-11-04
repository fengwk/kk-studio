package fun.fengwk.kkstudio.platform.harness.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.TokenEstimator;
import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/**
 * Platform 侧的 Harness 自动对话压缩配置与请求预算端口：每次决策点从 {@link SystemSettingsSnapshot} 的 {@code
 * aiRuntime.compactionKeepRecentTokens} 映射 {@link CompactionConfig}，并提供请求输入 token 估算的正式实现。
 *
 * <p>{@link CompactionConfigProvider} 同时被 platform 的 {@code DatabaseTurnResolver} 与 web 组合根的 {@code
 * ThreadProcessorConfig} 消费，作为 platform 的唯一定义点。aiRuntime 配置 live 生效，无需重启。
 *
 * <p>{@link RequestTokenEstimator} 是组合根对内核实现的唯一直连点：这里把端口绑定到 {@link
 * TokenEstimator#estimateRequestTokens} 的方法引用，消费方（{@code DatabaseTurnResolver}）只注入端口。该 bean 没有
 * 任何依赖，不引入 {@code HarnessRuntime}，因此 platform 独立测试上下文与 web 组合根都不会出现装配环或缺失 bean。
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

  /** 请求输入 token 估算：直接复用内核正式发送所用的唯一估算实现，本层不复制算法、不做兜底。 */
  @Bean
  public RequestTokenEstimator requestTokenEstimator() {
    return TokenEstimator::estimateRequestTokens;
  }
}
