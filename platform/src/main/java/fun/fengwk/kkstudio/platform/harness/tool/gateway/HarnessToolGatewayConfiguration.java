package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.plugin.PluginBranchViewLoader;
import fun.fengwk.kkstudio.platform.harness.tool.ToolContributionCatalog;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link PlatformToolGateway} 的 executor 与配置 bean 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessExecutionAdmissionProperties.class)
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

  @Bean(name = "toolExecutionAdmission")
  @ConditionalOnMissingBean(name = "toolExecutionAdmission")
  public ConcurrencyAdmission toolExecutionAdmission(
      HarnessExecutionAdmissionProperties properties) {
    return new ConcurrencyAdmission(properties.getTool());
  }

  /**
   * 生产 {@link PlatformToolGateway}：与测试共用唯一构造器，本方法解析 {@link HarnessRuntimeProperties} 的
   * workdir/environmentRoot 与 SystemSettings 的 resourceMaxBytes，并直接传两个 live suppliers——每次 Busy /
   * Overloaded 判定从 SystemSettingsSnapshot 现读 {@code tool.toolGatewayBusyRetryMillis} / {@code
   * tool.toolGatewayOverloadRetryMillis}。任何自定义 {@link ToolGateway} bean 都会抑制该默认实现。
   */
  @Bean
  @ConditionalOnBean(ResourceStore.class)
  @ConditionalOnMissingBean(ToolGateway.class)
  public PlatformToolGateway platformToolGateway(
      ToolContributionCatalog toolContributionCatalog,
      PluginCatalog pluginCatalog,
      PluginBranchViewLoader pluginBranchViewLoader,
      RemoteToolTransport remoteTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      HarnessRuntimeProperties runtimeProperties,
      SystemSettingsSnapshot systemSettingsSnapshot,
      @Qualifier("toolGatewayExecutor") ExecutorService toolGatewayExecutor,
      @Qualifier("toolExecutionAdmission") ConcurrencyAdmission admission,
      Clock clock) {
    HarnessRuntimeProperties properties =
        Objects.requireNonNull(runtimeProperties, "runtimeProperties");
    // 单对象资源字节预算：读取共享启动快照的 SystemSettings.Advanced.resourceMaxBytes（装配期一次 DB 读取，DB 变更需重启生效）。
    int resourceMaxBytes =
        Math.toIntExact(systemSettingsSnapshot.get().advanced().resourceMaxBytes());
    return new PlatformToolGateway(
        toolContributionCatalog,
        pluginCatalog,
        pluginBranchViewLoader,
        remoteTransport,
        permissionEvaluator,
        toolSettingsProvider,
        resourceStore,
        properties.resolvedWorkdir(),
        properties.resolvedEnvironmentRoot(),
        resourceMaxBytes,
        toolGatewayExecutor,
        () -> Duration.ofMillis(systemSettingsSnapshot.get().tool().toolGatewayBusyRetryMillis()),
        () ->
            Duration.ofMillis(systemSettingsSnapshot.get().tool().toolGatewayOverloadRetryMillis()),
        clock,
        admission);
  }
}
