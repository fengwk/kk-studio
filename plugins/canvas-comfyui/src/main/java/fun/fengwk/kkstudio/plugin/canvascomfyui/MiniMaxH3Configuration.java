package fun.fengwk.kkstudio.plugin.canvascomfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.time.Duration;

/** MiniMax-H3 Ref2VA adapter 自动装配；availability 与运行配置来自 SystemSettings.integrations.minimaxH3。 */
@AutoConfiguration
@EnableConfigurationProperties(MiniMaxH3Properties.class)
public class MiniMaxH3Configuration {

  @Bean
  @ConditionalOnMissingBean
  public H3MediaPreflight h3MediaPreflight() {
    return new H3MediaPreflight();
  }

  @Bean
  @ConditionalOnMissingBean
  public H3PromptRequestBuilder h3PromptRequestBuilder() {
    return new H3PromptRequestBuilder();
  }

  @Bean
  @ConditionalOnMissingBean
  public H3WorkflowBuilder h3WorkflowBuilder(ObjectMapper objectMapper) {
    return new H3WorkflowBuilder(objectMapper);
  }

  @Bean
  @DependsOn("systemProxySelector")
  public StandardComfyuiClient standardH3ComfyuiClient(
      MiniMaxH3Properties properties, SystemSettingsSnapshot snapshot, ObjectMapper objectMapper) {
    SystemSettings.MiniMaxH3 settings = snapshot.get().integrations().minimaxH3();
    if (!settings.enabled()) {
      return null;
    }
    properties.requireBearerToken();
    return new StandardComfyuiClient(
        settings.comfyBaseUrl(),
        properties.getComfyBearerToken(),
        Duration.ofMillis(settings.comfyConnectTimeoutMillis()),
        Duration.ofMillis(settings.comfyRequestTimeoutMillis()),
        objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public MiniMaxH3CanvasFunctionAdapter miniMaxH3CanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      ObjectMapper objectMapper) {
    return new MiniMaxH3CanvasFunctionAdapter(
        snapshot,
        mediaPreflight,
        promptBuilder,
        oneShotService,
        workflowBuilder,
        comfyClients,
        uploadService,
        refManager,
        objectMapper);
  }
}
