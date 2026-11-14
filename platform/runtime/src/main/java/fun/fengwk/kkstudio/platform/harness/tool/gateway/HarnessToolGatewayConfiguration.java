package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessExecutionAdmissionProperties;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Clock;
import java.time.Duration;
import java.util.function.IntSupplier;

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
   * 生产 {@link ToolExecutionGateway}：本方法从 SystemSettings 解析输出硬上限与 Gateway 过载重试间隔，各以一个 live supplier
   * 传入，每次都从 {@link SystemSettingsSnapshot} 现读（{@code tool.maxOutputMib} 与 {@code
   * tool.toolGatewayOverloadRetryMillis}）。任何自定义 {@link ToolGateway} bean 都会抑制该默认实现。
   */
  @Bean
  @ConditionalOnMissingBean(ToolGateway.class)
  public ToolExecutionGateway toolExecutionGateway(
      RuntimeToolCatalogSource runtimeToolCatalogSource,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      StorageUploadService storageUploadService,
      SystemSettingsSnapshot systemSettingsSnapshot,
      @Qualifier("toolExecutionAdmission") ConcurrencyAdmission admission,
      Clock clock) {
    IntSupplier outputLimitBytes =
        () -> Math.multiplyExact(systemSettingsSnapshot.get().tool().maxOutputMib(), 1_048_576);
    return new ToolExecutionGateway(
        runtimeToolCatalogSource,
        capabilityTransport,
        permissionEvaluator,
        toolSettingsProvider,
        new StorageUploadToolResourceStager(storageUploadService, outputLimitBytes),
        outputLimitBytes,
        () ->
            Duration.ofMillis(systemSettingsSnapshot.get().tool().toolGatewayOverloadRetryMillis()),
        clock,
        admission);
  }
}
