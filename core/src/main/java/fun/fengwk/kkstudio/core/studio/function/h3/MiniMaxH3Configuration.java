package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;

/** MiniMax-H3 Ref2VA adapter 装配。 */
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
  @ConditionalOnProperty(
      prefix = "kk-studio.canvas.function.minimax-h3",
      name = "enabled",
      havingValue = "true")
  public StandardComfyuiClient standardH3ComfyuiClient(
      MiniMaxH3Properties properties, ObjectMapper objectMapper) {
    properties.validateEnabled();
    return new StandardComfyuiClient(
        properties.getComfyBaseUrl(),
        properties.getComfyBearerToken(),
        properties.getComfyConnectTimeout(),
        properties.getComfyRequestTimeout(),
        objectMapper);
  }

  @Bean
  public MiniMaxH3CanvasFunctionAdapter miniMaxH3CanvasFunctionAdapter(
      MiniMaxH3Properties properties,
      H3MediaPreflight mediaPreflight,
      H3PromptRequestBuilder promptBuilder,
      HarnessOneShotService oneShotService,
      H3WorkflowBuilder workflowBuilder,
      ObjectProvider<StandardComfyuiClient> comfyClients,
      ObjectProvider<StorageBlobIngestService> ingestServices,
      ObjectMapper objectMapper) {
    return new MiniMaxH3CanvasFunctionAdapter(
        properties,
        mediaPreflight,
        promptBuilder,
        oneShotService,
        workflowBuilder,
        comfyClients,
        ingestServices,
        objectMapper);
  }
}
