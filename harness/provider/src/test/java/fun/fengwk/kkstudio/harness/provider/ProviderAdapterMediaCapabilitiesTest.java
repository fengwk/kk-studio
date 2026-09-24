package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatConfiguration;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.net.http.HttpClient;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 工具结果模态矩阵契约：锁定四个协议适配器声明的内联媒体能力，防止「声明支持但编码器无法表达」或「编码器支持却未声明」两类漂移。
 *
 * <p>矩阵本身对应官方协议事实，且与各协议编码器的实际 wire 行为由同包 {@code ProviderToolResultMediaMatrixWireTest} 在 HTTP
 * 边界逐格验证：
 *
 * <ul>
 *   <li>OpenAI Chat Completions：tool message 的 {@code content} 只能是字符串，工具结果不承载任何媒体。
 *   <li>OpenAI Responses：{@code function_call_output.output} 数组支持 {@code input_text} / {@code
 *       input_image} / {@code input_file}，因此工具结果支持 IMAGE 与 DOCUMENT(PDF)。
 *   <li>Anthropic Messages：{@code tool_result.content} 支持嵌套的 image 与 document(PDF) 块。
 *   <li>Gemini GenerateContent：媒体必须内联在 {@code functionResponse.parts[].inlineData}；工具结果只声明保守白名单
 *       （image/jpeg、image/png、image/webp 与 application/pdf），音频与视频不声明，由编码器以 {@code INVALID_REQUEST}
 *       明确拒绝。
 * </ul>
 *
 * <p>描述能力只表示本编码器能表达的 schema，不承诺具体上游模型支持该模态。
 */
class ProviderAdapterMediaCapabilitiesTest {

  /** 单个协议的期望矩阵：标签、适配器工厂与用户/工具结果两组期望模态。 */
  private record CapabilityExpectation(
      String protocol,
      Function<JdkHttpSseTransport, ProviderAdapter> adapterFactory,
      Set<ModelInputModality> userModalities,
      Set<ModelInputModality> toolResultModalities) {}

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

  /** 四个协议在缺省连接配置下的权威矩阵；Chat 的矩阵由 openAiChatMediaTypes 派生，单独在下方覆盖。 */
  private static Stream<Arguments> capabilityMatrix() {
    return Stream.of(
        Arguments.of(
            new CapabilityExpectation(
                "openai-chat-tool-results-are-text-only",
                transport -> new OpenAiChatProviderAdapter(transport, "key"),
                Set.of(),
                Set.of())),
        Arguments.of(
            new CapabilityExpectation(
                "openai-responses-image-and-pdf-in-both-positions",
                transport -> new OpenAiResponsesProviderAdapter(transport, "key"),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT))),
        Arguments.of(
            new CapabilityExpectation(
                "anthropic-image-and-pdf-in-both-positions",
                transport -> new AnthropicProviderAdapter(transport, "key"),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT))),
        Arguments.of(
            new CapabilityExpectation(
                "gemini-tool-results-are-image-and-pdf-only",
                transport -> new GeminiProviderAdapter(transport, "key"),
                Set.of(
                    ModelInputModality.IMAGE,
                    ModelInputModality.AUDIO,
                    ModelInputModality.VIDEO,
                    ModelInputModality.DOCUMENT),
                Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT))));
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

  /** 逐协议验证声明的用户/工具结果模态矩阵；矩阵与官方协议事实、编码器 wire 行为三方一致。 */
  @ParameterizedTest(name = "media capability matrix: {0}")
  @MethodSource("capabilityMatrix")
  void adaptersDeclareProtocolMediaMatrix(CapabilityExpectation expectation) {
    ProviderAdapter adapter = expectation.adapterFactory().apply(transport);
    assertMatrix(
        adapter.mediaCapabilities(),
        expectation.userModalities(),
        expectation.toolResultModalities());
    // 音频与视频永不作为工具结果声明：四个协议的工具结果位置都不承载这两种模态
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.AUDIO, true));
    assertFalse(adapter.mediaCapabilities().supports(ModelInputModality.VIDEO, true));
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

  /** 未显式声明能力的 adapter 必须落到 NONE；未声明的 adapter 只能退化到资源文本回退。 */
  @Test
  void undeclaredAdapterFallsBackToNone() {
    ProviderAdapter undeclared =
        new ProviderAdapter() {
          @Override
          public ProviderType providerType() {
            return ProviderType.OPENAI;
          }

          @Override
          public ModelProvider create(ProviderDescriptor descriptor) {
            throw new UnsupportedOperationException("capability contract test does not create");
          }
        };
    assertMatrix(undeclared.mediaCapabilities(), Set.of(), Set.of());
    assertEquals(ProviderMediaCapabilities.NONE, undeclared.mediaCapabilities());
  }

  /** 能力声明与 adapter 元数据不得泄露凭据。 */
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
      assertFalse(adapter.mediaCapabilities().toString().contains(sensitiveKey));
    }
  }
}
