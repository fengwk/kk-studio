package fun.fengwk.kkstudio.platform.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRefreshTrigger;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
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
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;

import java.time.Clock;

/**
 * Plugin 控制面的装配。
 *
 * <p>{@link StudioPlugin} bean 由各 Plugin JAR 自己的 auto-configuration 提供；没有 Plugin 时安装目录为空，管理面返回空列表。
 * 凭据只有一条写入路径：{@link PluginConfigurationService}。运行期目录 {@link PluginCatalogRuntime} 由 Web 组合根装配。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PluginProperties.class)
public class PluginConfiguration {

  @Bean
  public StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
    return new StudioPluginRegistry(plugins.orderedStream().toList());
  }

  @Bean
  public PluginCredentialStore pluginCredentialStore(PluginCredentialRepository repository) {
    return new DatabasePluginCredentialStore(repository, Clock.systemUTC());
  }

  @Bean
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
  public PluginConfigurationService pluginConfigurationService(
      StudioPluginRegistry registry,
      PluginConfigurationRepository configurations,
      PluginCredentialRepository credentials,
      PluginContextFactory contextFactory,
      PluginAgentDependencies agentDependencies,
      PlatformTransactionManager transactionManager,
      ObjectProvider<PluginCatalogRefreshTrigger> catalogRefreshTrigger,
      ObjectProvider<PluginCredentialRefreshDispatcher> refreshDispatcher) {
    return new PluginConfigurationService(
        registry,
        configurations,
        credentials,
        contextFactory,
        agentDependencies,
        transactionManager,
        () -> catalogRefreshTrigger.getObject().requestRefresh(),
        () -> refreshDispatcher.getObject().wake());
  }

  @Bean
  public PluginManagementService pluginManagementService(
      StudioPluginRegistry registry,
      ObjectProvider<PluginCatalogRuntime> catalogRuntime,
      PluginConfigurationRepository configurations,
      PluginCredentialRepository credentials,
      PluginConfigurationService configurationService,
      PluginAuthAttemptRepository authAttempts,
      PluginProperties properties) {
    return new PluginManagementService(
        registry,
        catalogRuntime::getObject,
        configurations,
        credentials,
        configurationService,
        authAttempts,
        properties.getAuthAttemptTtl(),
        Clock.systemUTC());
  }

  @Bean
  public PluginCredentialRefreshService pluginCredentialRefreshService(
      StudioPluginRegistry registry,
      PluginCredentialRepository repository,
      PluginProperties properties) {
    return new PluginCredentialRefreshService(registry, repository, properties, Clock.systemUTC());
  }

  @Bean
  public PluginCredentialRefreshDispatcher pluginCredentialRefreshDispatcher(
      PluginCredentialRefreshService refreshService, PluginProperties properties) {
    return new PluginCredentialRefreshDispatcher(refreshService, properties, Clock.systemUTC());
  }
}
