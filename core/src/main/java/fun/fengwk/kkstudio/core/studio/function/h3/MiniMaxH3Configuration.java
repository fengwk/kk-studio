package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;

/** MiniMax-H3 Ref2VA adapter 装配。 */
@Configuration
@EnableConfigurationProperties(MiniMaxH3Properties.class)
public class MiniMaxH3Configuration {

  @Bean
  public H3MediaPreflight h3MediaPreflight(ObjectMapper objectMapper) {
    return new H3MediaPreflight(objectMapper);
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
    validateEnabled(properties);
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
      ObjectMapper objectMapper) {
    return new MiniMaxH3CanvasFunctionAdapter(
        properties,
        mediaPreflight,
        promptBuilder,
        oneShotService,
        workflowBuilder,
        comfyClients,
        objectMapper);
  }

  private static void validateEnabled(MiniMaxH3Properties properties) {
    Assert.hasText(
        properties.getPromptAgentName(),
        "kk-studio.canvas.function.minimax-h3.prompt-agent-name must not be blank");
    Assert.hasText(
        properties.getPromptEnvironmentName(),
        "kk-studio.canvas.function.minimax-h3.prompt-environment-name must not be blank");
    Assert.isTrue(
        properties.getPresignExpirySeconds() > 0L,
        "kk-studio.canvas.function.minimax-h3.presign-expiry-seconds must be positive");
    Assert.notNull(
        properties.getPromptMaxWait(),
        "kk-studio.canvas.function.minimax-h3.prompt-max-wait must not be null");
    Assert.hasText(
        properties.getComfyBaseUrl(),
        "kk-studio.canvas.function.minimax-h3.comfy-base-url must not be blank");
    Assert.notNull(
        properties.getComfyConnectTimeout(),
        "kk-studio.canvas.function.minimax-h3.comfy-connect-timeout must not be null");
    Assert.notNull(
        properties.getComfyRequestTimeout(),
        "kk-studio.canvas.function.minimax-h3.comfy-request-timeout must not be null");
    Assert.notNull(
        properties.getComfyPollInterval(),
        "kk-studio.canvas.function.minimax-h3.comfy-poll-interval must not be null");
    Assert.notNull(
        properties.getComfyMaxWait(),
        "kk-studio.canvas.function.minimax-h3.comfy-max-wait must not be null");
  }
}
