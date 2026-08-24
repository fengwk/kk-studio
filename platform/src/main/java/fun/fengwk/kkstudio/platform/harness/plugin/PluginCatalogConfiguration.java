package fun.fengwk.kkstudio.platform.harness.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 收集 Spring 容器中的全部 {@link HarnessPlugin} bean，冻结为单一不可变 {@link PluginCatalog}；允许空插件列表。 catalog
 * 在启动时一次性构建，之后不再变化。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  @Bean
  public TrustedJarPluginLoader trustedJarPluginLoader(Environment environment) {
    String directory = environment.getProperty(TrustedJarPluginLoader.DIRECTORY_PROPERTY);
    if (directory == null || directory.isBlank()) {
      directory = environment.getProperty(TrustedJarPluginLoader.DIRECTORY_ENVIRONMENT_VARIABLE);
    }
    if (directory == null || directory.isBlank()) {
      directory = System.getenv(TrustedJarPluginLoader.DIRECTORY_ENVIRONMENT_VARIABLE);
    }
    return TrustedJarPluginLoader.fromConfiguredDirectory(directory);
  }

  @Bean
  public PluginCatalog pluginCatalog(
      ObjectProvider<HarnessPlugin> plugins, TrustedJarPluginLoader trustedJarPluginLoader) {
    List<HarnessPlugin> allPlugins = new ArrayList<>(plugins.orderedStream().toList());
    allPlugins.addAll(trustedJarPluginLoader.plugins());
    return PluginCatalog.from(allPlugins);
  }
}
