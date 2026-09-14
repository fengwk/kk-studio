package fun.fengwk.kkstudio.platform.canvas.function.h3;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;

/** 配置测试覆盖默认关闭装配、启用时的 token fail-fast 与聚合 runtime 边界。 */
class MiniMaxH3ConfigurationTest {

  private final MiniMaxH3Configuration configuration = new MiniMaxH3Configuration();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void createsAllBeansForValidEnabledConfiguration() {
    MiniMaxH3Properties properties = new MiniMaxH3Properties();
    properties.setComfyBearerToken("token");
    SystemSettingsSnapshot snapshot = snapshot(h3Settings(true));

    H3MediaPreflight preflight = configuration.h3MediaPreflight();
    H3PromptRequestBuilder promptBuilder = configuration.h3PromptRequestBuilder();
    H3WorkflowBuilder workflowBuilder = configuration.h3WorkflowBuilder(mapper);
    StandardComfyuiClient client =
        configuration.standardH3ComfyuiClient(properties, snapshot, mapper);

    assertNotNull(preflight);
    assertNotNull(promptBuilder);
    assertNotNull(workflowBuilder);
    assertNotNull(client);
    assertNotNull(
        configuration.miniMaxH3CanvasFunctionAdapter(
            snapshot,
            preflight,
            promptBuilder,
            mock(HarnessOneShotService.class),
            workflowBuilder,
            mock(ObjectProvider.class),
            mock(StorageBlobIngestService.class),
            mapper));
  }

  @Test
  @SuppressWarnings("unchecked")
  void disabledIntegrationBootsWithoutSecretAndStillRegistersAdapter() {
    MiniMaxH3Properties properties = new MiniMaxH3Properties();
    SystemSettingsSnapshot snapshot = snapshot(h3Settings(false));

    assertNull(configuration.standardH3ComfyuiClient(properties, snapshot, mapper));
    // adapter 始终注册，enabled() 由 SystemSettings.integrations.minimaxH3 决定
    assertNotNull(
        configuration.miniMaxH3CanvasFunctionAdapter(
            snapshot,
            configuration.h3MediaPreflight(),
            configuration.h3PromptRequestBuilder(),
            mock(HarnessOneShotService.class),
            configuration.h3WorkflowBuilder(mapper),
            mock(ObjectProvider.class),
            mock(StorageBlobIngestService.class),
            mapper));
  }

  @Test
  void enabledIntegrationRequiresBearerTokenSecret() {
    MiniMaxH3Properties properties = new MiniMaxH3Properties();
    SystemSettingsSnapshot snapshot = snapshot(h3Settings(true));

    assertThrows(
        IllegalStateException.class,
        () -> configuration.standardH3ComfyuiClient(properties, snapshot, mapper));
  }

  @Test
  void rejectsInvalidAggregateRuntimeBounds() {
    // enabled 时必须携带 promptAgentName 与 comfyBaseUrl
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true, null, 600_000L, "http://127.0.0.1:8188", 10_000L, 30_000L, 1_000L, 30_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true, "h3-agent", 600_000L, null, 10_000L, 30_000L, 1_000L, 30_000L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true, "h3-agent", 0L, "http://127.0.0.1:8188", 10_000L, 30_000L, 1_000L, 30_000L));
    // comfyPollIntervalMillis 必须严格小于 comfyMaxWaitMillis
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemSettings.MiniMaxH3(
                true,
                "h3-agent",
                600_000L,
                "http://127.0.0.1:8188",
                10_000L,
                30_000L,
                30_000L,
                30_000L));
  }

  private static SystemSettingsSnapshot snapshot(SystemSettings.MiniMaxH3 minimaxH3) {
    return new SystemSettingsSnapshot(
        new SystemSettings(
            SystemSettings.Tool.DEFAULT,
            SystemSettings.AiRuntime.DEFAULT,
            SystemSettings.Environment.DEFAULT,
            new SystemSettings.Integrations(
                SystemSettings.Comfyui.DEFAULT,
                SystemSettings.OpenCliHub.DEFAULT,
                SystemSettings.Seedance.DEFAULT,
                SystemSettings.GptImage2.DEFAULT,
                minimaxH3),
            SystemSettings.StorageMedia.DEFAULT,
            SystemSettings.Advanced.DEFAULT));
  }

  private static SystemSettings.MiniMaxH3 h3Settings(boolean enabled) {
    return new SystemSettings.MiniMaxH3(
        enabled, "h3-agent", 600_000L, "http://127.0.0.1:8188", 10_000L, 30_000L, 1_000L, 30_000L);
  }
}
