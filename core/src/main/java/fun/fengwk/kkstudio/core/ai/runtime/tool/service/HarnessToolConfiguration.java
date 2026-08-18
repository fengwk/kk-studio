package fun.fengwk.kkstudio.core.ai.runtime.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.tool.configuration.SystemSettingsToolSettingsProvider;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;

/** Tool settings 与 permission evaluator 的生产装配。 */
@Configuration(proxyBeanMethods = false)
public class HarnessToolConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsCodec toolSettingsCodec(ObjectMapper objectMapper) {
    return new ToolSettingsCodec(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsProvider toolSettingsProvider(SystemSettingsProvider systemSettingsProvider) {
    return new SystemSettingsToolSettingsProvider(systemSettingsProvider);
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
}
