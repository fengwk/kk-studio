package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Clock;

/** OpenCLI Hub 及其 Canvas Function adapters 始终注册，availability 由 SystemSettings.integrations 决定。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenCliHubProperties.class)
public class OpenCliCanvasFunctionConfiguration {

  @Bean
  OpenCliHubClient openCliHubClient(
      OpenCliHubProperties properties, SystemSettingsSnapshot snapshot, ObjectMapper objectMapper) {
    return new OpenCliHubClient(properties, snapshot, objectMapper);
  }

  @Bean
  GptImage2CanvasFunctionAdapter gptImage2CanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot, OpenCliHubClient client) {
    return new GptImage2CanvasFunctionAdapter(snapshot, client);
  }

  @Bean
  SeedanceCanvasFunctionAdapter seedanceCanvasFunctionAdapter(
      SystemSettingsSnapshot snapshot,
      OpenCliHubClient client,
      ObjectMapper objectMapper,
      Clock clock) {
    return new SeedanceCanvasFunctionAdapter(snapshot, client, objectMapper, clock);
  }
}
