package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginFunctionNamesProjection;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginContextFactory;

import java.time.Duration;
import java.util.List;

/**
 * 两域能力的唯一组合根：{@link PluginCatalogRuntime} 拥有当前 Plugin 目录快照。
 *
 * <p>快照按数据库中的当前配置构建，容器只持有安装定义与真正内置的贡献。平台只冻结 Harness 目录；Canvas 目录作为产品目录投影在每次快照构建时冻结一次，{@link
 * PluginCanvasFunctionCatalogSource} 与插件管理面的函数名投影读取同一份已冻结视图，{@link PluginCatalogRefresher}
 * 把通知、提交唤醒与周期对账合并成一次回读。
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
    List<CanvasFunctionAdapter> builtinAdapters = adapters.orderedStream().toList();
    return new PluginCatalogRuntime(
        registry,
        repository,
        contextFactory::create,
        contributors.orderedStream().toList(),
        snapshot -> PluginCanvasFunctionCatalog.from(snapshot, builtinAdapters));
  }

  @Bean
  public PluginCanvasFunctionCatalogSource pluginCanvasFunctionCatalogSource(
      PluginCatalogRuntime runtime) {
    return new PluginCanvasFunctionCatalogSource(runtime);
  }

  /** 管理面只看已冻结目录中该插件贡献的函数名；平台不解释 Canvas 类型。 */
  @Bean
  public PluginFunctionNamesProjection canvasFunctionNamesProjection() {
    return PluginCanvasFunctionCatalog::functionNames;
  }

  @Bean(destroyMethod = "close")
  public PluginCatalogRefresher pluginCatalogRefresher(PluginCatalogRuntime runtime) {
    return new PluginCatalogRefresher(runtime, REFRESH_INTERVAL);
  }
}
