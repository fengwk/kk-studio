package fun.fengwk.kkstudio.core.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.comfyui.ComfyUIClient;
import fun.fengwk.convention4j.comfyui.ComfyUIClientFactory;
import fun.fengwk.convention4j.comfyui.ComfyUIClientOptions;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiLookupService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/**
 * ComfyUI 无状态客户端与运行服务配置。
 *
 * @author fengwk
 */
@EnableConfigurationProperties(ComfyuiProperties.class)
@Configuration
public class ComfyuiConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ComfyUIClient.class)
  @ConditionalOnProperty(prefix = "kk-studio.comfyui", name = "enabled", havingValue = "true")
  public ComfyUIClient comfyUIClient(ComfyuiProperties properties) {
    Assert.hasText(properties.getBaseUrl(), "kk-studio.comfyui.base-url must not be blank");
    Assert.notNull(
        properties.getConnectTimeout(), "kk-studio.comfyui.connect-timeout must not be null");
    Assert.notNull(properties.getReadTimeout(), "kk-studio.comfyui.read-timeout must not be null");
    Assert.notNull(
        properties.getWebsocketTimeout(), "kk-studio.comfyui.websocket-timeout must not be null");
    return new ComfyUIClientFactory()
        .create(
            ComfyUIClientOptions.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .websocketTimeout(properties.getWebsocketTimeout())
                .build());
  }

  @Bean
  @ConditionalOnMissingBean(ComfyuiRuntimeService.class)
  public ComfyuiRuntimeService comfyuiRuntimeService(
      ComfyuiWorkflowApiLookupService workflowApiLookupService,
      ComfyuiProperties properties,
      ObjectProvider<ComfyUIClient> comfyUIClientProvider,
      ObjectProvider<S3StorageService> s3StorageServiceProvider,
      ObjectMapper objectMapper) {
    return new ComfyuiRuntimeService(
        workflowApiLookupService,
        properties,
        comfyUIClientProvider,
        s3StorageServiceProvider,
        objectMapper);
  }
}
