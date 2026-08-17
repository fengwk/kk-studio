package fun.fengwk.kkstudio.core.studio.function.opencli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** OpenCLI Hub 及其 Canvas Function adapters 始终注册，availability 由显式开关决定。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  OpenCliHubProperties.class,
  GptImage2CanvasProperties.class,
  SeedanceCanvasProperties.class
})
public class OpenCliCanvasFunctionConfiguration {

  @Bean
  OpenCliHubClient openCliHubClient(OpenCliHubProperties properties, ObjectMapper objectMapper) {
    return new OpenCliHubClient(properties, objectMapper);
  }

  @Bean
  GptImage2CanvasFunctionAdapter gptImage2CanvasFunctionAdapter(
      OpenCliHubProperties hubProperties,
      GptImage2CanvasProperties properties,
      OpenCliHubClient client) {
    return new GptImage2CanvasFunctionAdapter(hubProperties, properties, client);
  }

  @Bean
  SeedanceCanvasFunctionAdapter seedanceCanvasFunctionAdapter(
      OpenCliHubProperties hubProperties,
      SeedanceCanvasProperties properties,
      OpenCliHubClient client,
      ObjectMapper objectMapper,
      Clock clock) {
    return new SeedanceCanvasFunctionAdapter(
        hubProperties, properties, client, objectMapper, clock);
  }
}
