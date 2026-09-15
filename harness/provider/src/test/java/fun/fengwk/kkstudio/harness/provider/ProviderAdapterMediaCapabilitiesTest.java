package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatConfiguration;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;

import java.net.http.HttpClient;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 测试意图：验证四个协议适配器声明的内联媒体能力与各自编码器实际支持的 user/tool 媒体矩阵严格一致，防止能力声明与 encoder 能力漂移；并确认缺省未声明媒体能力的 {@link
 * ProviderAdapter#mediaCapabilities()} 默认值为 NONE。
 */
class ProviderAdapterMediaCapabilitiesTest {

  private static final Set<ModelInputModality> ALL_MEDIA =
      Set.of(
          ModelInputModality.IMAGE,
          ModelInputModality.AUDIO,
          ModelInputModality.VIDEO,
          ModelInputModality.DOCUMENT);

  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() {
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    workerExecutor = Executors.newSingleThreadExecutor();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (httpClient != null) {
      httpClient.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** 断言能力与期望矩阵逐格相等，避免只比较 size 而漏过具体模态差异。 */
  private static void assertMatrix(
      ProviderMediaCapabilities actual,
      Set<ModelInputModality> expectedUser,
      Set<ModelInputModality> expectedTool) {
    assertNotNull(actual);
    assertEquals(expectedUser, actual.userModalities());
    assertEquals(expectedTool, actual.toolResultModalities());
    for (ModelInputModality modality : ModelInputModality.values()) {
      assertEquals(
          expectedUser.contains(modality),
          actual.supports(modality, false),
          "user modality mismatch: " + modality);
      assertEquals(
          expectedTool.contains(modality),
          actual.supports(modality, true),
          "tool result modality mismatch: " + modality);
    }
  }

  @Test
  void openAiChatCapabilitiesDeriveFromConfiguredMediaTypes() {
    // 未配置任何媒体类型：默认即为无能力（不因代码默认全开而漂移）
    OpenAiChatProviderAdapter defaultAdapter = new OpenAiChatProviderAdapter(transport, "key");
    assertMatrix(defaultAdapter.mediaCapabilities(), Set.of(), Set.of());
    assertEquals(ProviderMediaCapabilities.NONE.userModalities(), Set.of());

    // IMAGE/AUDIO/PDF 配置映射为 IMAGE/AUDIO/DOCUMENT；工具结果永远为空
    OpenAiChatConfiguration allMedia =
        new OpenAiChatConfiguration(
            true,
            true,
            EnumSet.of(
                OpenAiChatConfiguration.MediaType.IMAGE,
                OpenAiChatConfiguration.MediaType.AUDIO,
                OpenAiChatConfiguration.MediaType.PDF),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    OpenAiChatProviderAdapter allAdapter =
        new OpenAiChatProviderAdapter(transport, "key", allMedia);
    assertMatrix(
        allAdapter.mediaCapabilities(),
        Set.of(ModelInputModality.IMAGE, ModelInputModality.AUDIO, ModelInputModality.DOCUMENT),
        Set.of());
    // VIDEO 永不声明：编码器对该模态直接拒绝
    assertFalse(allAdapter.mediaCapabilities().supports(ModelInputModality.VIDEO, false));

    // 仅 IMAGE 的配置只声明 IMAGE
    OpenAiChatConfiguration imageOnly =
        new OpenAiChatConfiguration(
            true,
            true,
            EnumSet.of(OpenAiChatConfiguration.MediaType.IMAGE),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    assertMatrix(
        new OpenAiChatProviderAdapter(transport, "key", imageOnly).mediaCapabilities(),
        Set.of(ModelInputModality.IMAGE),
        Set.of());

    // PDF 单独映射为 DOCUMENT（不产生 PDF 模态）
    OpenAiChatConfiguration pdfOnly =
        new OpenAiChatConfiguration(
            true,
            true,
            EnumSet.of(OpenAiChatConfiguration.MediaType.PDF),
            OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    assertMatrix(
        new OpenAiChatProviderAdapter(transport, "key", pdfOnly).mediaCapabilities(),
        Set.of(ModelInputModality.DOCUMENT),
        Set.of());
  }

  @Test
  void openAiResponsesCapabilitiesMatchEncoderMatrix() {
    OpenAiResponsesProviderAdapter adapter = new OpenAiResponsesProviderAdapter(transport, "key");
    assertMatrix(
        adapter.mediaCapabilities(),
        Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        Set.of(ModelInputModality.IMAGE));
    // 音频与视频在 Responses 编码器中不支持
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.AUDIO, false));
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.VIDEO, false));
    // 工具结果不支持 DOCUMENT
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.DOCUMENT, true));
  }

  @Test
  void anthropicCapabilitiesMatchEncoderMatrix() {
    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "key");
    assertMatrix(
        adapter.mediaCapabilities(),
        Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
        Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT));
    // Anthropic 编码器不接受音频与视频媒体块
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.AUDIO, false));
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.VIDEO, false));
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.AUDIO, true));
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.VIDEO, true));
  }

  @Test
  void geminiCapabilitiesMatchEncoderMatrix() {
    GeminiProviderAdapter adapter = new GeminiProviderAdapter(transport, "key");
    assertMatrix(adapter.mediaCapabilities(), ALL_MEDIA, ALL_MEDIA);
  }

  @Test
  void adaptersExposeCapabilitiesWithoutLeakingCredential() {
    String sensitiveKey = "sk-super-sensitive-secret-token-xyz-123456789";
    ProviderAdapter[] adapters =
        new ProviderAdapter[] {
          new OpenAiChatProviderAdapter(transport, sensitiveKey),
          new OpenAiResponsesProviderAdapter(transport, sensitiveKey),
          new AnthropicProviderAdapter(transport, sensitiveKey),
          new GeminiProviderAdapter(transport, sensitiveKey)
        };
    for (ProviderAdapter adapter : adapters) {
      assertEquals(adapter.providerType(), adapter.providerType(), "providerType must be stable");
      assertNotNull(adapter.mediaCapabilities());
      assertFalse(adapter.mediaCapabilities().userModalities().toString().contains(sensitiveKey));
      assertFalse(adapter.toString().contains(sensitiveKey));
      assertFalse(adapter.toString().contains("super-sensitive"));
      assertTrue(adapter.toString().startsWith(adapter.getClass().getSimpleName()));
    }
  }
}
