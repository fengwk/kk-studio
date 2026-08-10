package fun.fengwk.kkstudio.core.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;

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
    H3MediaPreflight preflight = configuration.h3MediaPreflight(mapper);
    H3PromptRequestBuilder promptBuilder = configuration.h3PromptRequestBuilder();
    H3WorkflowBuilder workflowBuilder = configuration.h3WorkflowBuilder(mapper);
    StandardComfyuiClient client = configuration.standardH3ComfyuiClient(properties, mapper);
    ObjectProvider<StandardComfyuiClient> clients = mock(ObjectProvider.class);

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
            mapper));
  }

  @Test
  void rejectsEveryMissingEnabledPropertyAtStartup() {
    List<Consumer<MiniMaxH3Properties>> invalidators =
        List.of(
            value -> value.setPromptAgentName(""),
            value -> value.setPromptEnvironmentName(""),
            value -> value.setPresignExpirySeconds(0L),
            value -> value.setPromptMaxWait(null),
            value -> value.setComfyBaseUrl(""),
            value -> value.setComfyConnectTimeout(null),
            value -> value.setComfyRequestTimeout(null),
            value -> value.setComfyPollInterval(null),
            value -> value.setComfyMaxWait(null));
    for (Consumer<MiniMaxH3Properties> invalidate : invalidators) {
      MiniMaxH3Properties properties = validProperties();
      invalidate.accept(properties);
      assertThrows(
          IllegalArgumentException.class,
          () -> configuration.standardH3ComfyuiClient(properties, mapper));
    }
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
