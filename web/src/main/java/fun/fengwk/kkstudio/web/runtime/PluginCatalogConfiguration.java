package fun.fengwk.kkstudio.web.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.canvas.function.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 两域能力的唯一组合根：启动时经 {@link PluginCatalogBootstrap} 初始化或按持久版本恢复，立即交给唯一 {@link PluginCatalogRuntime}
 * 拥有。
 *
 * <p>配置来自权威 catalog 与不可变普通配置历史（首次初始化写入 Schema 默认值），快照拥有配置实例，容器只持有安装定义与真正内置的贡献。运行期所有者是唯一热更新入口：
 * Canvas 目录经 {@link PluginCanvasFunctionCatalogSource} 按 epoch 租用同一份快照，{@link
 * PluginCatalogRefresher} 把通知、提交唤醒与周期对账合并成一次权威回读。
 */
@Configuration(proxyBeanMethods = false)
public class PluginCatalogConfiguration {

  /** 权威对账的固定周期：通知已提供低延迟，周期只保证漏通知时仍有界追平。 */
  static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);

  @Bean
  public PluginCatalogBootstrap pluginCatalogBootstrap(
      StudioPluginRegistry registry,
      PluginConfigurationRepository repository,
      PlatformTransactionManager transactionManager) {
    return new PluginCatalogBootstrap(registry, repository, transactionManager);
  }

  /**
   * 唯一运行期所有者：构造期一次 {@link PluginCatalogBootstrap#bootstrap} 得到初始快照，之后由本 bean 全权拥有并按 epoch 发布。
   *
   * <p>真正内置的两域贡献与 scoped {@link PluginContext} 工厂在这里冻结一次，同时供启动初始化和后续对账/历史重建使用，绝不出现两套内置来源。
   *
   * <p>Plugin 实例在 configure 期构造的客户端隐式继承 JVM 默认代理，因此本组合根显式依赖 {@code systemProxySelector}
   * 先完成安装：该顺序是宿主组合根 自身的装配约束，不依赖任何具体插件或插件的 {@code @DependsOn}，插件侧也不引用宿主类型或 Bean 名。
   */
  @Bean(destroyMethod = "close")
  @DependsOn("systemProxySelector")
  public PluginCatalogRuntime pluginCatalogRuntime(
      PluginCatalogBootstrap bootstrap,
      PluginConfigurationRepository repository,
      PluginCredentialStore credentials,
      ObjectProvider<PluginResourceGateway> resourceGateways,
      ObjectProvider<HarnessContributor> contributors,
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    BiFunction<String, PluginCredentialReference, PluginContext> contexts =
        pluginContexts(credentials, resourceGateways);
    List<HarnessContributor> builtins = contributors.orderedStream().toList();
    List<CanvasFunctionAdapter> builtinAdapters = adapters.orderedStream().toList();
    PluginCatalogSnapshot initial = bootstrap.bootstrap(contexts, builtins, builtinAdapters);
    return new PluginCatalogRuntime(
        bootstrap, repository, contexts, builtins, builtinAdapters, initial);
  }

  /** Canvas 目录的唯一生产来源：每次租用都钉住运行时的一个冻结 epoch，绝不再暴露静态目录。 */
  @Bean
  public PluginCanvasFunctionCatalogSource pluginCanvasFunctionCatalogSource(
      PluginCatalogRuntime runtime) {
    return new PluginCanvasFunctionCatalogSource(runtime);
  }

  /** 权威目录对账器：通知、提交唤醒与固定周期触发的唯一权威回读入口。 */
  @Bean(destroyMethod = "close")
  public PluginCatalogRefresher pluginCatalogRefresher(PluginCatalogRuntime runtime) {
    return new PluginCatalogRefresher(runtime, REFRESH_INTERVAL);
  }

  /** 组合根唯一的 scoped {@link PluginContext} 工厂：按 pluginId 与冻结凭据引用装配上下文。 */
  static BiFunction<String, PluginCredentialReference, PluginContext> pluginContexts(
      PluginCredentialStore credentials, ObjectProvider<PluginResourceGateway> resourceGateways) {
    PluginContextFactory factory =
        new PluginContextFactory(credentials, resourceGateways::getIfAvailable);
    return factory::create;
  }
}
