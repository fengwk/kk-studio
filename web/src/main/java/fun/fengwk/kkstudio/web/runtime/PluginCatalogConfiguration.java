package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.ScopedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.Optional;
import java.util.function.Function;

/**
 * 两域能力的唯一组合根：启动时经 {@link PluginCatalogBootstrap} 初始化或按持久版本恢复，再提供同一快照中的 Harness 与 Canvas 目录。
 *
 * <p>配置来自权威 catalog 与不可变普通配置历史（首次初始化写入 Schema 默认值），快照拥有配置实例，容器只持有安装定义与真正内置的贡献。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  @Bean
  public PluginCatalogBootstrap pluginCatalogBootstrap(
      StudioPluginRegistry registry,
      PluginConfigurationRepository repository,
      PlatformTransactionManager transactionManager) {
    return new PluginCatalogBootstrap(registry, repository, transactionManager);
  }

  @Bean(destroyMethod = "close")
  public PluginCatalogSnapshot pluginCatalogSnapshot(
      PluginCatalogBootstrap bootstrap,
      PluginCredentialStore credentials,
      ObjectProvider<PluginResourceGateway> resourceGateways,
      ObjectProvider<HarnessContributor> contributors,
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    return bootstrap.bootstrap(
        pluginContexts(credentials, resourceGateways),
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

  /** 组合根唯一的 scoped {@link PluginContext} 工厂。 */
  static Function<String, PluginContext> pluginContexts(
      PluginCredentialStore credentials, ObjectProvider<PluginResourceGateway> resourceGateways) {
    return pluginId ->
        new PluginContext(
            new ScopedPluginCredentialAccess(pluginId, credentials),
            Optional.ofNullable(resourceGateways.getIfAvailable()));
  }
}
