package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.ai.runtime.plugin.PluginBranchViewLoader;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link CoreToolGateway} 的 executor 与配置 bean 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessToolGatewayConfiguration {

  /**
   * admission 之后 Platform Tool 执行与缓冲回调重放专用的 executor。 Java 21 虚拟线程天然适合阻塞的远程 Tool 发送；{@code
   * destroyMethod = "close"} 让在途任务由 Spring 生命周期持有，但不会等待它们。
   */
  @Bean(name = "toolGatewayExecutor", destroyMethod = "close")
  @ConditionalOnMissingBean(name = "toolGatewayExecutor")
  public ExecutorService toolGatewayExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolGatewayConfig toolGatewayConfig() {
    return ToolGatewayConfig.DEFAULT;
  }

  @Bean
  @ConditionalOnBean(ResourceStore.class)
  @ConditionalOnMissingBean(CoreToolGateway.class)
  public CoreToolGateway coreToolGateway(
      ToolFactories toolFactories,
      PluginCatalog pluginCatalog,
      PluginBranchViewLoader pluginBranchViewLoader,
      RemoteToolTransport remoteTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      HarnessRuntimeProperties runtimeProperties,
      @Qualifier("toolGatewayExecutor") ExecutorService toolGatewayExecutor,
      ToolGatewayConfig config,
      Clock clock) {
    return new CoreToolGateway(
        toolFactories,
        pluginCatalog,
        pluginBranchViewLoader,
        remoteTransport,
        permissionEvaluator,
        toolSettingsProvider,
        resourceStore,
        runtimeProperties,
        toolGatewayExecutor,
        config,
        clock);
  }
}
