package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.plugin.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.plugin.goal.GoalPlugin;

/**
 * 收集 Spring 容器中的全部 {@link HarnessPlugin} bean，冻结为单一不可变 {@link PluginCatalog}；允许空插件列表。 catalog
 * 在启动时一次性构建，之后不再变化。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "goalPlugin")
  public HarnessPlugin goalPlugin() {
    return new GoalPlugin();
  }

  @Bean
  public PluginCatalog pluginCatalog(ObjectProvider<HarnessPlugin> plugins) {
    return PluginCatalog.from(plugins.orderedStream().toList());
  }
}
