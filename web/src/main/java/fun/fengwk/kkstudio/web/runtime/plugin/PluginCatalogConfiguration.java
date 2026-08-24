package fun.fengwk.kkstudio.web.runtime.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.platform.harness.plugin.HarnessPluginSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Web 组合根收集 Spring 容器中的 {@link HarnessPlugin} bean 与外部 {@link HarnessPluginSource} 快照，冻结为单一不可变
 * {@link PluginCatalog}。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  @Bean(destroyMethod = "close")
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
      ObjectProvider<HarnessPlugin> plugins, ObjectProvider<HarnessPluginSource> sources) {
    List<HarnessPlugin> allPlugins = new ArrayList<>(plugins.orderedStream().toList());
    sources
        .orderedStream()
        .forEach(
            source ->
                allPlugins.addAll(
                    List.copyOf(Objects.requireNonNull(source.plugins(), "source.plugins"))));
    return PluginCatalog.from(allPlugins);
  }
}
