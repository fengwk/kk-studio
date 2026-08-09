package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;

/**
 * Core 侧的 Harness 自动对话压缩配置：绑定 {@link HarnessRuntimeProperties} 并提供唯一的压缩配置事实。
 *
 * <p>{@link CompactionConfig} 同时被 core 的 {@code DatabaseTurnResolver} 与 web 组合根的 {@code
 * ThreadProcessorConfig} 消费，因此放在 core 作为唯一定义点；web 不再重复声明。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessCompactionConfiguration {

  /** 自动对话压缩配置：开关、预留 token 预算与保留的最近上下文 token 上限。 */
  @Bean
  public CompactionConfig compactionConfig(HarnessRuntimeProperties properties) {
    return new CompactionConfig(
        properties.isCompactionEnabled(),
        properties.getCompactionReserveTokens(),
        properties.getCompactionMaxRecentTokens());
  }
}
