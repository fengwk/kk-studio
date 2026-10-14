package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.ScopedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 两域能力的唯一组合根：每个插件配置一次，完整校验后提供同一快照中的 Harness 与 Canvas 目录。
 *
 * <p>初始配置来自安装定义的 Schema；快照拥有配置实例，容器只持有安装定义与真正内置的贡献。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  @Bean(destroyMethod = "close")
  public PluginCatalogSnapshot pluginCatalogSnapshot(
      StudioPluginRegistry registry,
      PluginCredentialStore credentials,
      ObjectProvider<PluginResourceGateway> resourceGateways,
      ObjectProvider<HarnessContributor> contributors,
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    Map<String, PluginConfig> configurations = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      configurations.put(
          plugin.descriptor().pluginId(), PluginConfig.initial(plugin.configurationSchema()));
    }
    return PluginCatalogSnapshot.build(
        registry,
        configurations,
        pluginId ->
            new PluginContext(
                new ScopedPluginCredentialAccess(pluginId, credentials),
                Optional.ofNullable(resourceGateways.getIfAvailable())),
        contributors.orderedStream().toList(),
        adapters.orderedStream().toList());
  }

  @Bean
  public HarnessCatalog harnessCatalog(PluginCatalogSnapshot snapshot) {
    return snapshot.harnessCatalog();
  }

  @Bean
  public CanvasFunctionCatalog canvasFunctionCatalog(PluginCatalogSnapshot snapshot) {
    return snapshot.canvasFunctionCatalog();
  }
}
