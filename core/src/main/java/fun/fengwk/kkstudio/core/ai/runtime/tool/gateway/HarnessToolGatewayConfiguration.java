package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link CoreToolGateway} 的 executor 与配置 bean 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessRuntimeProperties.class)
public class HarnessToolGatewayConfiguration {

  /**
   * Dedicated executor for post-admission Platform Tool execution and buffered callback replay.
   * Java 21 virtual threads are a natural fit for blocking remote Tool sends; {@code destroyMethod
   * = "close"} keeps in-flight tasks owned by Spring lifecycle but does not wait.
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
      RemoteToolTransport remoteTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      HarnessRuntimeProperties runtimeProperties,
      @Qualifier("toolGatewayExecutor") ExecutorService toolGatewayExecutor,
      ToolGatewayConfig config) {
    return new CoreToolGateway(
        toolFactories,
        remoteTransport,
        permissionEvaluator,
        toolSettingsProvider,
        resourceStore,
        runtimeProperties,
        toolGatewayExecutor,
        config);
  }
}
