package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;

import java.time.Duration;

/** MiniMax-H3 Ref2VA adapter 装配；availability 与运行配置来自 SystemSettings.integrations.minimaxH3。 */
@Configuration
@EnableConfigurationProperties(MiniMaxH3Properties.class)
public class MiniMaxH3Configuration {

  @Bean
  public H3MediaPreflight h3MediaPreflight() {
    return new H3MediaPreflight();
  }

  @Bean
  public H3PromptRequestBuilder h3PromptRequestBuilder() {
    return new H3PromptRequestBuilder();
  }

  @Bean
  public H3WorkflowBuilder h3WorkflowBuilder(ObjectMapper objectMapper) {
    return new H3WorkflowBuilder(objectMapper);
  }

  @Bean
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
  public MiniMaxH3CanvasFunctionAdapter miniMaxH3CanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      ObjectProvider<StorageBlobIngestService> ingestServices,
      ObjectMapper objectMapper) {
    return new MiniMaxH3CanvasFunctionAdapter(
        snapshot,
        mediaPreflight,
        promptBuilder,
        oneShotService,
        workflowBuilder,
        comfyClients,
        ingestServices,
        objectMapper);
  }
}
