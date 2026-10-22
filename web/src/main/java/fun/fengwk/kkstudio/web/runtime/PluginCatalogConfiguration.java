package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;

import java.time.Duration;

/**
 * 两域能力的唯一组合根：{@link PluginCatalogRuntime} 拥有当前 Plugin 目录快照。
 *
 * <p>快照按数据库中的当前配置构建，容器只持有安装定义与真正内置的贡献。Canvas 目录经 {@link PluginCanvasFunctionCatalogSource}
 * 租用同一份快照，{@link PluginCatalogRefresher} 把通知、提交唤醒与周期对账合并成一次回读。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  /** 对账的固定周期：通知已提供低延迟，周期只保证漏通知时仍有界追平。 */
  static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);

  /**
   * 唯一运行期所有者。
   *
   * <p>Plugin 实例在 configure 期构造的客户端隐式继承 JVM 默认代理，因此显式依赖 {@code systemProxySelector} 先完成安装。
   */
  @Bean(destroyMethod = "close")
  @DependsOn("systemProxySelector")
  public PluginCatalogRuntime pluginCatalogRuntime(
      StudioPluginRegistry registry,
      PluginConfigurationRepository repository,
      PluginContextFactory contextFactory,
      ObjectProvider<HarnessContributor> contributors,
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    return new PluginCatalogRuntime(
        registry,
        repository,
        contextFactory::create,
        contributors.orderedStream().toList(),
        adapters.orderedStream().toList());
  }

  @Bean
  public PluginCanvasFunctionCatalogSource pluginCanvasFunctionCatalogSource(
      PluginCatalogRuntime runtime) {
    return new PluginCanvasFunctionCatalogSource(runtime);
  }

  @Bean(destroyMethod = "close")
  public PluginCatalogRefresher pluginCatalogRefresher(PluginCatalogRuntime runtime) {
    return new PluginCatalogRefresher(runtime, REFRESH_INTERVAL);
  }
}
