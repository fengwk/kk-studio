package fun.fengwk.kkstudio.platform.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshService;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;

import java.time.Clock;

/**
 * Plugin 控制面的组合根装配。
 *
 * <p>所有 bean 都无条件装配：没有 Plugin JAR 时安装目录自然为空，管理面只返回空列表，刷新 dispatcher 无行可领；缺失必要依赖（例如 mapper）必须在启动期明确
 * 失败，绝不静默降级。{@link StudioPlugin} bean 由各 Plugin JAR 自己的 auto-configuration 提供，Platform 不扫描目录也不加载外部
 * classloader。
 *
 * <p>凭据只有一条写入路径：{@link PluginConfigurationCommitService} 在 catalog CAS 事务内按基线条件写行；读取侧 store
 * 不提供任何写入口。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PluginProperties.class)
public class PluginConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public PluginCredentialCodec pluginCredentialCodec() {
    return new PluginCredentialCodec();
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginCredentialKeyLoader pluginCredentialKeyLoader(PluginProperties properties) {
    return new PluginCredentialKeyLoader(properties.getCredentialKeyFile());
  }

  @Bean
  @ConditionalOnMissingBean
  public StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
    return new StudioPluginRegistry(plugins.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginCredentialStore pluginCredentialStore(
      PluginCredentialRepository repository,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader) {
    return new DatabasePluginCredentialStore(
        repository, configurationRepository, codec, keyLoader, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginContextFactory pluginContextFactory(
      PluginCredentialStore credentials,
      ObjectProvider<PluginResourceGateway> resourceGateways,
      ObjectProvider<PluginPromptTasks> promptTasks,
      ObjectProvider<PluginPromptResources> promptResources) {
    return new PluginContextFactory(
        credentials,
        resourceGateways::getIfAvailable,
        promptTasks::getIfAvailable,
        promptResources::getIfAvailable);
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginConfigurationCommitService pluginConfigurationCommitService(
      StudioPluginRegistry registry,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialRepository credentialRepository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      PluginContextFactory contextFactory,
      ObjectProvider<HarnessContributor> contributors,
      ObjectProvider<CanvasFunctionAdapter> adapters,
      PlatformTransactionManager transactionManager,
      ObjectProvider<PluginCredentialRefreshDispatcher> refreshDispatcher,
      ObjectProvider<PluginCatalogRefreshTrigger> catalogRefreshTrigger,
      PluginAgentDependencies pluginAgentDependencies) {
    return new PluginConfigurationCommitService(
        registry,
        configurationRepository,
        credentialRepository,
        codec,
        keyLoader,
        contextFactory,
        () -> contributors.orderedStream().toList(),
        () -> adapters.orderedStream().toList(),
        transactionManager,
        Clock.systemUTC(),
        () -> wakeRefreshDispatcher(refreshDispatcher),
        () -> wakeCatalogRefresh(catalogRefreshTrigger),
        pluginAgentDependencies);
  }

  /** 提交服务已经将回调排到提交后；这里只唤醒调度，不再注册事务同步。 */
  private static void wakeRefreshDispatcher(
      ObjectProvider<PluginCredentialRefreshDispatcher> refreshDispatcher) {
    PluginCredentialRefreshDispatcher dispatcher = refreshDispatcher.getIfAvailable();
    if (dispatcher != null) {
      dispatcher.wake();
    }
  }

  /** 提交服务已经将回调排到提交后；这里只请求一次本节点权威目录对账，通知丢失时周期对账仍能追平。 */
  private static void wakeCatalogRefresh(
      ObjectProvider<PluginCatalogRefreshTrigger> catalogRefreshTrigger) {
    catalogRefreshTrigger.getObject().requestRefresh();
  }

  /** applied 运行期以 {@link ObjectProvider} 惰性获取：管理面只在真正投影「本节点已应用视图」时才向其索取租用，装配期绝不提前解析，也不伪造空快照。 */
  @Bean
  @ConditionalOnMissingBean
  public PluginManagementService pluginManagementService(
      StudioPluginRegistry registry,
      ObjectProvider<PluginCatalogRuntime> catalogRuntime,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialStore credentialStore,
      PluginConfigurationCommitService commitService,
      PluginAuthAttemptRepository authAttempts,
      PluginProperties properties) {
    return new PluginManagementService(
        registry,
        catalogRuntime::getObject,
        configurationRepository,
        credentialStore,
        commitService,
        authAttempts,
        properties.getAuthAttemptTtl(),
        Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginCredentialRefreshService pluginCredentialRefreshService(
      StudioPluginRegistry registry,
      PluginCredentialRepository repository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      PluginProperties properties) {
    return new PluginCredentialRefreshService(
        registry, repository, codec, keyLoader, properties, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginCredentialRefreshDispatcher pluginCredentialRefreshDispatcher(
      PluginCredentialRefreshService refreshService, PluginProperties properties) {
    return new PluginCredentialRefreshDispatcher(refreshService, properties, Clock.systemUTC());
  }
}
