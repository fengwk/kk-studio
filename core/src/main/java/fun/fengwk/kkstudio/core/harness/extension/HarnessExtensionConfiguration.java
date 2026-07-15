package fun.fengwk.kkstudio.core.harness.extension;

import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtension;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将 Core 内置扩展与应用显式扩展装配到单一 Harness Host。 */
@Configuration(proxyBeanMethods = false)
public class HarnessExtensionConfiguration {
  @Bean
  public CoreHarnessExtension coreHarnessExtension(
      PermissionEvaluator permissionEvaluator, List<Tool> tools) {
    return new CoreHarnessExtension(permissionEvaluator, tools);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public HarnessExtensionHost harnessExtensionHost(List<HarnessExtension> extensions) {
    return HarnessExtensionHost.load(extensions);
  }

  @Bean
  @ConditionalOnMissingBean
  public HarnessLifecycleObservers harnessLifecycleObservers(HarnessExtensionHost host) {
    return new HarnessLifecycleObservers(host.lifecycleObservers());
  }
}
