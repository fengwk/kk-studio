package fun.fengwk.kkstudio.platform.plugin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshDispatcher;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialRefreshService;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;

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
      PluginCredentialKeyLoader keyLoader,
      ObjectProvider<PluginCredentialRefreshDispatcher> refreshDispatcher) {
    return new DatabasePluginCredentialStore(
        repository,
        codec,
        keyLoader,
        Clock.systemUTC(),
        pluginId -> wakeAfterCommit(refreshDispatcher));
  }

  /** 凭据保存成功后唤醒刷新调度：有活跃事务时排到提交后，避免通知早于新凭据可见；无事务时立即唤醒。 */
  private static void wakeAfterCommit(
      ObjectProvider<PluginCredentialRefreshDispatcher> refreshDispatcher) {
    PluginCredentialRefreshDispatcher dispatcher = refreshDispatcher.getIfAvailable();
    if (dispatcher == null) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatcher.wake();
            }
          });
    } else {
      dispatcher.wake();
    }
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
    return new PluginCredentialRefreshDispatcher(refreshService, properties, Clock.systemUTC());
  }
}
