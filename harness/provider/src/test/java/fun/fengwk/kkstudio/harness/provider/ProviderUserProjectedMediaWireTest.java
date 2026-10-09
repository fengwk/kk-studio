package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

/**
 * 「TOOL 原生位置不支持、USER 位置支持」时平台追加 USER 媒体消息的真实协议编码契约（离线，不调用任何 Provider）。
 *
 * <p>平台物化器在该场景下产出：TOOL 结果保留确定性文本事实，其后追加一条 USER 消息，先给出真实来源 toolCallId/blobId 的说明文本，再携带
 * 真实媒体块。本测试只把这一形态交给四个真实编码器，逐格验证：
 *
 * <ul>
 *   <li>支持的模态：媒体真实字节出现在协议正确的 USER 位置，且严格排在对应 TOOL 结果之后，绝不退化 成文本描述或地址；
 *   <li>不支持的模态：编码器以 {@code INVALID_REQUEST} 明确失败，且错误信息不包含任何媒体载荷。
 * </ul>
 *
 * <p>工具结果原生位置的 wire 契约由 {@link ProviderToolResultMediaMatrixWireTest} 覆盖，本测试只补 USER 投影面，不重复其断言。
 */
class ProviderUserProjectedMediaWireTest {

  private static final String TOOL_CALL_ID = "call-read-1";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final byte[] MEDIA_BYTES = new byte[] {0, 1, 2};
  private static final String MEDIA_BASE64 = Base64.getEncoder().encodeToString(MEDIA_BYTES);
  private static final Set<ModelInputModality> ALL_MODALITIES =
      Set.of(
          ModelInputModality.TEXT,
          ModelInputModality.IMAGE,
          ModelInputModality.AUDIO,
          ModelInputModality.VIDEO,
          ModelInputModality.DOCUMENT);

  private ExecutorService worker;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() {
    worker = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(client, worker, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (worker != null) {
      worker.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /**
   * 可被平台投影为追加 USER 消息的 (协议, 模态) 组合：TOOL 位置不支持、USER 位置支持，且模型声明该模态。
   *
   * <p>与各 adapter 的 {@code ProviderMediaCapabilities} 矩阵一致：OpenAI Chat 的用户位置支持
   * IMAGE/AUDIO/DOCUMENT， Gemini 的用户位置支持 AUDIO/VIDEO（其 IMAGE/DOCUMENT 走原生 TOOL 位置，不在本表）。
   */
  private static Stream<Arguments> projectedCases() {
    return Stream.of(
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.IMAGE, "image/png"),
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.AUDIO, "audio/wav"),
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.DOCUMENT, "application/pdf"),
        Arguments.of(Protocol.GEMINI, ModelInputModality.AUDIO, "audio/wav"),
        Arguments.of(Protocol.GEMINI, ModelInputModality.VIDEO, "video/mp4"));
  }

  /** 任一位置都不支持该模态的 (协议, 模态) 组合：即使平台已按 USER 形态构造消息，协议编码器也必须 fail closed，绝不静默丢弃。 */
  private static Stream<Arguments> failClosedCases() {
    return Stream.of(
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.VIDEO, "video/mp4"),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.AUDIO, "audio/wav"),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.VIDEO, "video/mp4"),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.AUDIO, "audio/wav"),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.VIDEO, "video/mp4"));
  }

  @ParameterizedTest(name = "{0} projects {1} into an appended USER message")
  @MethodSource("projectedCases")
  void projectedMediaCarriesRealBytesAfterToolResult(
      Protocol protocol, ModelInputModality modality, String mediaType) throws Exception {
    ProviderAdapter adapter = adapterFor(protocol);
    byte[] body = adapter.encodeRequestBody(request(modality, mediaType), descriptorFor(protocol));
    JsonNode json = MAPPER.readTree(body);
    String dataUri = dataUri(mediaType);

    // 真实 base64 载荷必须逐字节出现在请求体中（不是路径、描述或"上传成功"文案）。
    assertTrue(new String(body, StandardCharsets.UTF_8).contains(MEDIA_BASE64));
    switch (protocol) {
      case OPENAI_CHAT -> assertOpenAiChat(json, modality, mediaType, dataUri);
      case GEMINI -> assertGemini(json, modality, mediaType);
      default -> throw new IllegalStateException("no projected USER case for " + protocol);
    }
  }

  @ParameterizedTest(name = "{0} rejects {1} instead of dropping it")
  @MethodSource("failClosedCases")
  void unsupportedUserMediaFailsClosedWithoutLeakingPayload(
      Protocol protocol, ModelInputModality modality, String mediaType) {
    ProviderAdapter adapter = adapterFor(protocol);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () -> adapter.encodeRequestBody(request(modality, mediaType), descriptorFor(protocol)));

    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertFalse(
        error.getMessage().contains(MEDIA_BASE64),
        "unsupported media error must not echo the payload");
  }

  /** OpenAI Chat：媒体在追加 USER 消息的 content 数组内，且该消息严格排在 tool 消息之后。 */
  private static void assertOpenAiChat(
      JsonNode body, ModelInputModality modality, String mediaType, String dataUri) {
    JsonNode messages = body.get("messages");
    int toolIndex = indexOfMessageWithRole(messages, "tool");
    int mediaIndex = indexOfUserMessageContainingMedia(messages, modality);
    assertTrue(toolIndex >= 0, "tool message must be present");
    assertTrue(mediaIndex > toolIndex, "appended user media must follow the tool message");

    JsonNode part = messages.get(mediaIndex).get("content").get(1);
    switch (modality) {
      case IMAGE -> {
        assertEquals("image_url", part.get("type").asText());
        assertEquals(dataUri, part.get("image_url").get("url").asText());
      }
      case AUDIO -> {
        assertEquals("input_audio", part.get("type").asText());
        assertEquals(MEDIA_BASE64, part.get("input_audio").get("data").asText());
        assertEquals("wav", part.get("input_audio").get("format").asText());
      }
      case DOCUMENT -> {
        assertEquals("file", part.get("type").asText());
        assertEquals(dataUri, part.get("file").get("file_data").asText());
        assertEquals("pdf_file", part.get("file").get("filename").asText());
      }
      default -> throw new IllegalStateException("unexpected projected modality " + modality);
    }
  }

  /** Gemini：媒体作为 USER 轮次顶层 part 的 inlineData，排在 functionResponse 之后且不在其内部。 */
  private static void assertGemini(JsonNode body, ModelInputModality modality, String mediaType) {
    JsonNode contents = body.get("contents");
    int userContentIndex = indexOfContentWithPart(contents, "functionResponse");
    assertTrue(userContentIndex >= 0, "functionResponse must be present");
    JsonNode parts = contents.get(userContentIndex).get("parts");
    int functionResponseIndex = indexOfPartWith(parts, "functionResponse");
    int inlineIndex = indexOfPartWith(parts, "inlineData");
    assertTrue(inlineIndex > functionResponseIndex, "inlineData must follow functionResponse");
    assertFalse(
        parts.get(functionResponseIndex).get("functionResponse").has("parts"),
        "tool functionResponse must not carry media bytes");

    JsonNode inlineData = parts.get(inlineIndex).get("inlineData");
    assertEquals(mediaType, inlineData.get("mimeType").asText());
    assertEquals(MEDIA_BASE64, inlineData.get("data").asText());
  }

  /** 平台物化器在 USER 位置投影工具结果媒体时产生的消息形态。 */
  private static ProviderRequest request(ModelInputModality modality, String mediaType) {
    ModelDescriptor model =
        new ModelDescriptor("provider", "test-model", "test-model", ALL_MODALITIES, true, false);
    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("run the tool"))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(
                    new ProviderToolCallBlock(new ProviderToolCall(TOOL_CALL_ID, "read", "{}")))),
            new ProviderMessage(
                ProviderMessageRole.TOOL,
                List.of(
                    new ProviderToolResultBlock(
                        TOOL_CALL_ID,
                        "read",
                        List.of(new ProviderTextBlock("resource facts")),
                        false,
                        "{}"))),
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(new ProviderTextBlock(sourceNote()), mediaBlock(modality, mediaType)))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static String sourceNote() {
    return "Tool result media from the preceding tool call(s) is delivered as user content."
        + " Sources:\n- tool call "
        + TOOL_CALL_ID
        + ", resource 00000000-0000-0000-0000-000000000001 (3 bytes)";
  }

  private static ProviderContentBlock mediaBlock(ModelInputModality modality, String mediaType) {
    String source = dataUri(mediaType);
    return switch (modality) {
      case IMAGE -> new ProviderImageBlock(mediaType, source);
      case AUDIO -> new ProviderAudioBlock(mediaType, source);
      case VIDEO -> new ProviderVideoBlock(mediaType, source);
      case DOCUMENT -> new ProviderDocumentBlock(mediaType, source);
      case TEXT -> throw new IllegalArgumentException("TEXT is not a media modality");
    };
  }

  private static String dataUri(String mediaType) {
    return "data:" + mediaType + ";base64," + MEDIA_BASE64;
  }

  private ProviderAdapter adapterFor(Protocol protocol) {
    return switch (protocol) {
      case OPENAI_CHAT -> new OpenAiChatProviderAdapter(transport, "key");
      case OPENAI_RESPONSES -> new OpenAiResponsesProviderAdapter(transport, "key");
      case ANTHROPIC -> new AnthropicProviderAdapter(transport, "key");
      case GEMINI -> new GeminiProviderAdapter(transport, "key");
    };
  }

  private ProviderDescriptor descriptorFor(Protocol protocol) {
    return new ProviderDescriptor(
        protocol.label,
        protocol.providerType,
        "http://127.0.0.1:18080" + protocol.basePath,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  private static int indexOfMessageWithRole(JsonNode messages, String role) {
    for (int i = 0; i < messages.size(); i++) {
      if (role.equals(messages.get(i).path("role").asText())) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfUserMessageContainingMedia(
      JsonNode messages, ModelInputModality modality) {
    String partType =
        switch (modality) {
          case IMAGE -> "image_url";
          case AUDIO -> "input_audio";
          case DOCUMENT -> "file";
          default -> throw new IllegalStateException("unexpected projected modality " + modality);
        };
    for (int i = 0; i < messages.size(); i++) {
      JsonNode content = messages.get(i).get("content");
      if (content != null && content.isArray() && indexOfPartWith(content, partType) >= 0) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfContentWithPart(JsonNode contents, String field) {
    for (int i = 0; i < contents.size(); i++) {
      JsonNode parts = contents.get(i).get("parts");
      if (parts != null && indexOfPartWith(parts, field) >= 0) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfPartWith(JsonNode parts, String field) {
    for (int i = 0; i < parts.size(); i++) {
      if (parts.get(i).has(field)) {
        return i;
      }
    }
    return -1;
  }

  private enum Protocol {
    OPENAI_CHAT("openai-chat", "/v1", ProviderType.OPENAI),
    OPENAI_RESPONSES("openai-responses", "/v1", ProviderType.OPENAI_RESPONSES),
    ANTHROPIC("anthropic", "/v1", ProviderType.ANTHROPIC),
    GEMINI("gemini", "/v1beta", ProviderType.GOOGLE);

    private final String label;
    private final String basePath;
    private final ProviderType providerType;

    Protocol(String label, String basePath, ProviderType providerType) {
      this.label = label;
      this.basePath = basePath;
      this.providerType = providerType;
    }
  }
}
