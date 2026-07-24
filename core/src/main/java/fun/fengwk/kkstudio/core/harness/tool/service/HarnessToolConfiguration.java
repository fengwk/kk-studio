package fun.fengwk.kkstudio.core.harness.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.tool.configuration.DeploymentToolSettingsProvider;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProperties;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;

/** Tool settings、permission evaluator 与执行 interceptor 的生产装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolSettingsProperties.class)
public class HarnessToolConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsCodec toolSettingsCodec(ObjectMapper objectMapper) {
    return new ToolSettingsCodec(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsProvider toolSettingsProvider(
      ToolSettingsProperties properties, ToolSettingsCodec codec) {
    return new DeploymentToolSettingsProvider(properties, codec);
  }

  @Bean
  @ConditionalOnMissingBean
  public BashSurfaceAnalyzer bashSurfaceAnalyzer() {
    return new BashSurfaceAnalyzer();
  }

  @Bean
  @ConditionalOnMissingBean
  public PermissionEvaluator permissionEvaluator(
      ObjectMapper objectMapper, BashSurfaceAnalyzer bashSurfaceAnalyzer) {
    return new PermissionEvaluator(objectMapper, bashSurfaceAnalyzer);
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolInterceptorChain toolInterceptorChain(HarnessExtensionHost host) {
    return new ToolInterceptorChain(
        host.beforeToolCallInterceptors(), host.afterToolCallInterceptors());
  }
}
