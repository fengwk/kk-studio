package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Clock;
import java.time.Duration;

/** {@link ToolExecutionGateway} 的并发准入与配置 bean 装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HarnessExecutionAdmissionProperties.class)
public class HarnessToolGatewayConfiguration {

  @Bean(name = "toolExecutionAdmission")
  @ConditionalOnMissingBean(name = "toolExecutionAdmission")
  public ConcurrencyAdmission toolExecutionAdmission(
      HarnessExecutionAdmissionProperties properties) {
    return new ConcurrencyAdmission(properties.getTool());
  }

  /**
   * 生产 {@link ToolExecutionGateway}：与测试共用唯一构造器，本方法解析 SystemSettings 的 resourceMaxBytes，并直接传 live
   * supplier——每次 RetryLater 判定从 SystemSettingsSnapshot 现读 {@code
   * tool.toolGatewayOverloadRetryMillis}。任何自定义 {@link ToolGateway} bean 都会抑制该默认实现。
   */
  @Bean
  @ConditionalOnMissingBean(ToolGateway.class)
  public ToolExecutionGateway toolExecutionGateway(
      RuntimeToolCatalogSource runtimeToolCatalogSource,
      ContributorBranchViewLoader contributorBranchViewLoader,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      StorageUploadService storageUploadService,
      SystemSettingsSnapshot systemSettingsSnapshot,
      @Qualifier("toolExecutionAdmission") ConcurrencyAdmission admission,
      Clock clock) {
    int resourceMaxBytes =
        Math.toIntExact(systemSettingsSnapshot.get().advanced().resourceMaxBytes());
    return new ToolExecutionGateway(
        runtimeToolCatalogSource,
        contributorBranchViewLoader,
        capabilityTransport,
        permissionEvaluator,
        toolSettingsProvider,
        new StorageUploadToolResourceStager(storageUploadService, resourceMaxBytes),
        resourceMaxBytes,
        () ->
            Duration.ofMillis(systemSettingsSnapshot.get().tool().toolGatewayOverloadRetryMillis()),
        clock,
        admission);
  }
}
