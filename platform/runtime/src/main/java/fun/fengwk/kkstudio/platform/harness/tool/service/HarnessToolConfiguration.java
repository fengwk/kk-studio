package fun.fengwk.kkstudio.platform.harness.tool.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.harness.tool.configuration.SystemSettingsToolSettingsProvider;
import fun.fengwk.kkstudio.platform.harness.tool.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsCodec;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

/** Tool settings 与 permission evaluator 的生产装配。 */
@Configuration(proxyBeanMethods = false)
public class HarnessToolConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsCodec toolSettingsCodec() {
    return new ToolSettingsCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolSettingsProvider toolSettingsProvider(SystemSettingsSnapshot systemSettingsSnapshot) {
    return new SystemSettingsToolSettingsProvider(systemSettingsSnapshot);
  }

  @Bean
  @ConditionalOnMissingBean
  public BashSurfaceAnalyzer bashSurfaceAnalyzer() {
    return new BashSurfaceAnalyzer();
  }

  @Bean
  @ConditionalOnMissingBean
  public PermissionEvaluator permissionEvaluator(BashSurfaceAnalyzer bashSurfaceAnalyzer) {
    return new PermissionEvaluator(bashSurfaceAnalyzer);
  }
}
