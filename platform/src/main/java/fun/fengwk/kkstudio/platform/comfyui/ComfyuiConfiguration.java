package fun.fengwk.kkstudio.platform.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.comfyui.ComfyUIClient;
import fun.fengwk.convention4j.comfyui.ComfyUIClientFactory;
import fun.fengwk.convention4j.comfyui.ComfyUIClientOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiLookupService;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;

import java.time.Duration;
import java.util.Objects;

/**
 * ComfyUI 无状态客户端与运行服务配置。
 *
 * <p>客户端是长生命周期拓扑，装配开关来自 SystemSettings.integrations.comfyui.enabled（不再是 properties 属性）：关闭时
 * 不创建客户端且无需 baseUrl/apiKey（SystemSettings 仅在启用时要求 baseUrl）；启用时 bootstrap 缺失仍明确报错。
 *
 * @author fengwk
 */
@EnableConfigurationProperties(ComfyuiProperties.class)
@Configuration
public class ComfyuiConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ComfyUIClient.class)
  public ComfyUIClient comfyUIClient(
      ComfyuiProperties properties, SystemSettingsSnapshot snapshot) {
    SystemSettings.Comfyui settings = snapshot.get().integrations().comfyui();
    if (!settings.enabled()) {
      return null;
    }
    return new ComfyUIClientFactory()
        .create(
            ComfyUIClientOptions.builder()
                .baseUrl(Objects.requireNonNull(settings.baseUrl(), "comfyui baseUrl"))
                .apiKey(properties.getApiKey())
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMillis()))
                .readTimeout(Duration.ofMillis(settings.readTimeoutMillis()))
                .websocketTimeout(Duration.ofMillis(settings.websocketTimeoutMillis()))
                .build());
  }

  @Bean
  @ConditionalOnMissingBean(ComfyuiRuntimeService.class)
  public ComfyuiRuntimeService comfyuiRuntimeService(
      ComfyuiWorkflowApiLookupService workflowApiLookupService,
      SystemSettingsSnapshot snapshot,
      ObjectProvider<ComfyUIClient> comfyUIClientProvider,
      ObjectProvider<S3StorageService> s3StorageServiceProvider,
      ObjectMapper objectMapper) {
    return new ComfyuiRuntimeService(
        workflowApiLookupService,
        snapshot,
        comfyUIClientProvider,
        s3StorageServiceProvider,
        objectMapper);
  }
}
