package fun.fengwk.kkstudio.platform.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshService;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.resource.StoragePluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Clock;

/**
 * Plugin 控制面的组合根装配。
 *
 * <p>所有 bean 都无条件装配：没有 Plugin JAR 时安装目录自然为空，管理面只返回空列表，刷新 dispatcher 无行可领；缺失必要依赖（例如 mapper）必须在启动期明确
 * 失败，绝不静默降级。{@link StudioPlugin} bean 由各 Plugin JAR 自己的 auto-configuration 提供，Platform 不扫描目录也不加载外部
 * classloader。
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

  /**
   * Plugin 资源端口的生产实现：会话 Resource 授权下载 + 远端媒体真实暂存。
   *
   * <p>它不依赖任何具体 Plugin：没有 Plugin 时只是没有调用方。存储与 Harness 事务句柄都是 Platform 自身的 bean，因此本能力随 Platform
   * 一起装配。
   */
  @Bean
  @ConditionalOnMissingBean
  public PluginResourceGateway pluginResourceGateway(
      HarnessStore harnessStore,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties) {
    return new StoragePluginResourceGateway(
        harnessStore, sessionBlobRefManager, storageBlobManager, storageUploadService, properties);
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
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader) {
    return new DatabasePluginCredentialStore(repository, codec, keyLoader, Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean
  public PluginManagementService pluginManagementService(
      StudioPluginRegistry registry, PluginCredentialStore credentialStore) {
    return new PluginManagementService(registry, credentialStore);
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
    return new PluginCredentialRefreshDispatcher(refreshService, properties);
  }
}
