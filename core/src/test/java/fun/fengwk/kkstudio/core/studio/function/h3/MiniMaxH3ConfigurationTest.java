package fun.fengwk.kkstudio.core.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** 配置测试覆盖默认关闭装配和启用时所有必需连接参数的 fail-fast 校验。 */
class MiniMaxH3ConfigurationTest {

  private final MiniMaxH3Configuration configuration = new MiniMaxH3Configuration();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void createsAllBeansForValidConfiguration() {
    MiniMaxH3Properties properties = validProperties();
    H3MediaPreflight preflight = configuration.h3MediaPreflight();
    H3PromptRequestBuilder promptBuilder = configuration.h3PromptRequestBuilder();
    H3WorkflowBuilder workflowBuilder = configuration.h3WorkflowBuilder(mapper);
    StandardComfyuiClient client = configuration.standardH3ComfyuiClient(properties, mapper);
    ObjectProvider<StandardComfyuiClient> clients = mock(ObjectProvider.class);
    ObjectProvider<StorageBlobIngestService> ingestServices = mock(ObjectProvider.class);

    assertNotNull(preflight);
    assertNotNull(promptBuilder);
    assertNotNull(workflowBuilder);
    assertNotNull(client);
    assertNotNull(
        configuration.miniMaxH3CanvasFunctionAdapter(
            properties,
            preflight,
            promptBuilder,
            mock(HarnessOneShotService.class),
            workflowBuilder,
            clients,
            ingestServices,
            mapper));
  }

  @Test
  void acceptsExactRuntimeBounds() {
    MiniMaxH3Properties properties = validProperties();
    properties.setPromptAgentName("a".repeat(MiniMaxH3Properties.MAX_AGENT_NAME_LENGTH));
    properties.setPromptEnvironmentName("a".repeat(64));
    properties.setPresignExpirySeconds(1L);
    properties.setPromptMaxWait(MiniMaxH3Properties.MAX_PROMPT_WAIT);
    properties.setComfyConnectTimeout(MiniMaxH3Properties.MAX_COMFY_CONNECT_TIMEOUT);
    properties.setComfyRequestTimeout(MiniMaxH3Properties.MAX_COMFY_REQUEST_TIMEOUT);
    properties.setComfyPollInterval(MiniMaxH3Properties.MAX_COMFY_POLL_INTERVAL);
    properties.setComfyMaxWait(MiniMaxH3Properties.MAX_COMFY_WAIT);
    assertDoesNotThrow(properties::validateEnabled);

    properties.setPresignExpirySeconds(MiniMaxH3Properties.MAX_PRESIGN_EXPIRY_SECONDS);
    assertDoesNotThrow(properties::validateEnabled);
  }

  @Test
  void rejectsInvalidIdentityAndRuntimeBounds() {
    List<Consumer<MiniMaxH3Properties>> invalidators =
        List.of(
            value -> value.setPromptAgentName(null),
            value -> value.setPromptAgentName(""),
            value -> value.setPromptAgentName(" h3-agent"),
            value -> value.setPromptAgentName("h3/agent"),
            value ->
                value.setPromptAgentName("a".repeat(MiniMaxH3Properties.MAX_AGENT_NAME_LENGTH + 1)),
            value -> value.setPromptEnvironmentName(null),
            value -> value.setPromptEnvironmentName(""),
            value -> value.setPromptEnvironmentName("H3-prompt"),
            value -> value.setPromptEnvironmentName("h3/prompt"),
            value -> value.setPromptEnvironmentName("-h3"),
            value -> value.setPromptEnvironmentName("a".repeat(65)),
            value -> value.setPresignExpirySeconds(0L),
            value -> value.setPresignExpirySeconds(-1L),
            value ->
                value.setPresignExpirySeconds(MiniMaxH3Properties.MAX_PRESIGN_EXPIRY_SECONDS + 1L),
            value -> value.setPromptMaxWait(null),
            value -> value.setPromptMaxWait(Duration.ZERO),
            value -> value.setPromptMaxWait(Duration.ofNanos(-1L)),
            value -> value.setPromptMaxWait(MiniMaxH3Properties.MAX_PROMPT_WAIT.plusNanos(1L)),
            value -> value.setComfyConnectTimeout(null),
            value -> value.setComfyConnectTimeout(Duration.ZERO),
            value -> value.setComfyConnectTimeout(Duration.ofNanos(-1L)),
            value ->
                value.setComfyConnectTimeout(
                    MiniMaxH3Properties.MAX_COMFY_CONNECT_TIMEOUT.plusNanos(1L)),
            value -> value.setComfyRequestTimeout(null),
            value -> value.setComfyRequestTimeout(Duration.ZERO),
            value -> value.setComfyRequestTimeout(Duration.ofNanos(-1L)),
            value ->
                value.setComfyRequestTimeout(
                    MiniMaxH3Properties.MAX_COMFY_REQUEST_TIMEOUT.plusNanos(1L)),
            value -> value.setComfyPollInterval(null),
            value -> value.setComfyPollInterval(Duration.ZERO),
            value -> value.setComfyPollInterval(Duration.ofNanos(-1L)),
            value ->
                value.setComfyPollInterval(
                    MiniMaxH3Properties.MAX_COMFY_POLL_INTERVAL.plusNanos(1L)),
            value -> value.setComfyMaxWait(null),
            value -> value.setComfyMaxWait(Duration.ZERO),
            value -> value.setComfyMaxWait(Duration.ofNanos(-1L)),
            value -> value.setComfyMaxWait(MiniMaxH3Properties.MAX_COMFY_WAIT.plusNanos(1L)),
            value -> {
              value.setComfyPollInterval(Duration.ofSeconds(1));
              value.setComfyMaxWait(Duration.ofSeconds(1));
            },
            value -> {
              value.setComfyPollInterval(Duration.ofSeconds(2));
              value.setComfyMaxWait(Duration.ofSeconds(1));
            });
    for (Consumer<MiniMaxH3Properties> invalidate : invalidators) {
      MiniMaxH3Properties properties = validProperties();
      invalidate.accept(properties);
      assertThrows(IllegalArgumentException.class, properties::validateEnabled);
    }
  }

  @Test
  void configurationUsesTheSingleRuntimeValidationEntry() {
    MiniMaxH3Properties properties = validProperties();
    properties.setPromptEnvironmentName("H3-prompt");
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration.standardH3ComfyuiClient(properties, mapper));
  }

  private static MiniMaxH3Properties validProperties() {
    MiniMaxH3Properties properties = new MiniMaxH3Properties();
    properties.setEnabled(true);
    properties.setPromptAgentName("h3-agent");
    properties.setPromptEnvironmentName("h3-prompt");
    properties.setPresignExpirySeconds(600L);
    properties.setPromptMaxWait(Duration.ofMinutes(10));
    properties.setComfyBaseUrl("http://127.0.0.1:8188");
    properties.setComfyConnectTimeout(Duration.ofSeconds(1));
    properties.setComfyRequestTimeout(Duration.ofSeconds(1));
    properties.setComfyPollInterval(Duration.ofSeconds(1));
    properties.setComfyMaxWait(Duration.ofMinutes(1));
    return properties;
  }
}
