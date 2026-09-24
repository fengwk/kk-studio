package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 工具结果模态矩阵的 wire 契约（本地离线 HttpServer，不调用任何真实 Provider）。
 *
 * <p>该测试把三方事实钉在一起：adapter 的 {@code ProviderMediaCapabilities} 声明、编码器生成的真实 HTTP 请求体、以及不支持的 模态必须 fail
 * closed。逐格验证：
 *
 * <ul>
 *   <li>声明支持时：请求确实发往上游，且媒体出现在协议正确的位置（Responses {@code input_image}/{@code input_file}、Anthropic
 *       {@code tool_result.content[]}、Gemini {@code functionResponse.parts[].inlineData}）；媒体 base64
 *       载荷逐字节保留。
 *   <li>声明不支持时：编码器以 {@code INVALID_REQUEST} 明确失败，且绝不发起 HTTP 请求，避免「假装已读取媒体」。
 * </ul>
 */
class ProviderToolResultMediaMatrixWireTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String TOOL_CALL_ID = "call_1";
  private static final String TOOL_NAME = "get_media";
  private static final String PNG_DATA_URI = "data:image/png;base64,iVBORw0KGgo=";
  private static final String PDF_DATA_URI = "data:application/pdf;base64,JVBERi0xLjQK";

  private static final Set<ModelInputModality> ALL_MODALITIES =
      Set.of(
          ModelInputModality.TEXT,
          ModelInputModality.IMAGE,
          ModelInputModality.AUDIO,
          ModelInputModality.VIDEO,
          ModelInputModality.DOCUMENT);

  /** 权威工具结果矩阵：能力声明与 wire 行为都必须与它一致。 */
  private static final Map<Protocol, Set<ModelInputModality>> EXPECTED_TOOL_RESULT_MODALITIES =
      Map.of(
          Protocol.OPENAI_CHAT, Set.of(),
          Protocol.OPENAI_RESPONSES, Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
          Protocol.ANTHROPIC, Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT),
          Protocol.GEMINI, Set.of(ModelInputModality.IMAGE, ModelInputModality.DOCUMENT));

  /** 被测协议及其端点解析与 wire 模型。 */
  private enum Protocol {
    OPENAI_CHAT("openai-chat", "/v1", "gpt-4o", ProviderType.OPENAI),
    OPENAI_RESPONSES("openai-responses", "/v1", "gpt-5.4-mini", ProviderType.OPENAI_RESPONSES),
    ANTHROPIC("anthropic", "/v1", "claude-3-5-sonnet", ProviderType.ANTHROPIC),
    GEMINI("gemini", "/v1beta", "gemini-2.5-flash", ProviderType.GOOGLE);

    private final String label;
    private final String basePath;
    private final String modelId;
    private final ProviderType providerType;

    Protocol(String label, String basePath, String modelId, ProviderType providerType) {
      this.label = label;
      this.basePath = basePath;
      this.modelId = modelId;
      this.providerType = providerType;
    }
  }

  private static final class RecordingHandler implements ProviderStreamHandler {
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicReference<ProviderException> error = new AtomicReference<>();

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {
      terminal.countDown();
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      this.error.compareAndSet(null, error);
      terminal.countDown();
    }

    void awaitTerminal() throws InterruptedException {
      terminal.await(10, TimeUnit.SECONDS);
    }

    ProviderException error() {
      return error.get();
    }
  }

  private HttpServer server;
  private int port;
  private HttpClient client;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;
  private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();
  private CountDownLatch requestLatch;

  @BeforeEach
  void setUp() throws IOException {
    requestLatch = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          capturedBody.set(body);
          byte[] response = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, response.length);
          try (var out = exchange.getResponseBody()) {
            out.write(response);
          }
          requestLatch.countDown();
        });
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    transport = new JdkHttpSseTransport(client, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (client != null) {
      client.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  private static Stream<Arguments> toolResultCases() {
    return Stream.of(
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.IMAGE),
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.DOCUMENT),
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.AUDIO),
        Arguments.of(Protocol.OPENAI_CHAT, ModelInputModality.VIDEO),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.IMAGE),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.DOCUMENT),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.AUDIO),
        Arguments.of(Protocol.OPENAI_RESPONSES, ModelInputModality.VIDEO),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.IMAGE),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.DOCUMENT),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.AUDIO),
        Arguments.of(Protocol.ANTHROPIC, ModelInputModality.VIDEO),
        Arguments.of(Protocol.GEMINI, ModelInputModality.IMAGE),
        Arguments.of(Protocol.GEMINI, ModelInputModality.DOCUMENT),
        Arguments.of(Protocol.GEMINI, ModelInputModality.AUDIO),
        Arguments.of(Protocol.GEMINI, ModelInputModality.VIDEO));
  }

  @ParameterizedTest(name = "{0} tool result {1}")
  @MethodSource("toolResultCases")
  void toolResultMediaMatrixHoldsAtHttpBoundary(Protocol protocol, ModelInputModality modality)
      throws Exception {
    ProviderAdapter adapter = adapterFor(protocol);
    boolean declared = adapter.mediaCapabilities().supports(modality, true);
    assertEquals(
        EXPECTED_TOOL_RESULT_MODALITIES.get(protocol).contains(modality),
        declared,
        "declared capability must equal the authoritative tool result matrix");

    ModelProvider provider = adapter.create(descriptorFor(protocol));
    RecordingHandler handler = new RecordingHandler();
    provider.stream(request(protocol, modality), handler);

    if (!declared) {
      handler.awaitTerminal();
      ProviderException error = handler.error();
      assertNotNull(error, "unsupported tool result modality must fail explicitly");
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertNotNull(error.getMessage());
      // fail closed：绝不上游发起一个「媒体被丢弃」的请求
      assertFalse(requestLatch.await(300, TimeUnit.MILLISECONDS), "no HTTP request must be sent");
      return;
    }

    assertTrue(requestLatch.await(10, TimeUnit.SECONDS), "supported modality must reach upstream");
    JsonNode body = MAPPER.readTree(capturedBody.get());
    assertMediaNestedCorrectly(protocol, modality, body);
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
        "http://127.0.0.1:" + port + protocol.basePath,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  }

  /** 请求形态：USER 指令 → ASSISTANT tool call → TOOL 结果（文本 + 媒体），用于验证媒体与该次调用的绑定。 */
  private static ProviderRequest request(Protocol protocol, ModelInputModality modality) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelDescriptor model =
        new ModelDescriptor(
            protocol.label,
            protocol.modelId,
            protocol.modelId,
            ALL_MODALITIES,
            true,
            false,
            pricing);
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
                    new ProviderToolCallBlock(
                        new ProviderToolCall(TOOL_CALL_ID, TOOL_NAME, "{}")))),
            new ProviderMessage(
                ProviderMessageRole.TOOL,
                List.of(
                    new ProviderToolResultBlock(
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        List.of(new ProviderTextBlock("result"), mediaBlock(modality)),
                        false,
                        "{}")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderContentBlock mediaBlock(ModelInputModality modality) {
    return switch (modality) {
      case IMAGE -> new ProviderImageBlock("image/png", PNG_DATA_URI);
      case DOCUMENT -> new ProviderDocumentBlock("application/pdf", PDF_DATA_URI);
      case AUDIO -> new ProviderAudioBlock("audio/wav", "data:audio/wav;base64,UklGRg==");
      case VIDEO -> new ProviderVideoBlock("video/mp4", "data:video/mp4;base64,AAAAIGZ0eXA=");
      case TEXT -> throw new IllegalArgumentException("TEXT is not a tool result media modality");
    };
  }

  /** 逐协议断言工具结果媒体的 wire 位置：媒体必须绑定在该次工具调用内部，而不是作为独立输入。 */
  private static void assertMediaNestedCorrectly(
      Protocol protocol, ModelInputModality modality, JsonNode body) {
    switch (protocol) {
      case OPENAI_RESPONSES -> {
        JsonNode output = findResponsesToolOutput(body);
        assertTrue(output.isArray(), "tool result media must be an output array");
        // 文本结果 + 媒体：两者按原顺序进入同一个 output 数组
        assertEquals(2, output.size());
        assertEquals("input_text", output.get(0).path("type").asText());
        assertEquals("result", output.get(0).path("text").asText());
        if (modality == ModelInputModality.IMAGE) {
          assertEquals("input_image", output.get(1).path("type").asText());
          assertEquals(PNG_DATA_URI, output.get(1).path("image_url").asText());
        } else {
          assertEquals("input_file", output.get(1).path("type").asText());
          assertEquals(PDF_DATA_URI, output.get(1).path("file_data").asText());
          assertEquals("document.pdf", output.get(1).path("filename").asText());
        }
      }
      case ANTHROPIC -> {
        JsonNode nested = findAnthropicToolResult(body).path("content");
        // 文本结果 + 媒体：两者按原顺序嵌套在同一个 tool_result.content 内
        assertEquals(2, nested.size());
        assertEquals("text", nested.get(0).path("type").asText());
        assertEquals("result", nested.get(0).path("text").asText());
        JsonNode block = nested.get(1);
        assertEquals("base64", block.path("source").path("type").asText());
        if (modality == ModelInputModality.IMAGE) {
          assertEquals("image", block.path("type").asText());
          assertEquals("image/png", block.path("source").path("media_type").asText());
          assertEquals("iVBORw0KGgo=", block.path("source").path("data").asText());
        } else {
          assertEquals("document", block.path("type").asText());
          assertEquals("application/pdf", block.path("source").path("media_type").asText());
          assertEquals("JVBERi0xLjQK", block.path("source").path("data").asText());
        }
      }
      case GEMINI -> {
        JsonNode parts = findGeminiToolResultContent(body).path("parts");
        // 媒体只作为 functionResponse 内部的 part，绝不与 functionResponse 同级
        assertEquals(1, parts.size(), "media must not be a sibling part of functionResponse");
        JsonNode functionResponse = parts.get(0).path("functionResponse");
        assertEquals(TOOL_NAME, functionResponse.path("name").asText());
        assertEquals(TOOL_CALL_ID, functionResponse.path("id").asText());
        assertEquals("result", functionResponse.path("response").path("result").asText());
        JsonNode nested = functionResponse.path("parts");
        assertEquals(1, nested.size());
        JsonNode inlineData = nested.get(0).path("inlineData");
        assertFalse(inlineData.has("displayName"), "v1beta schema has no displayName field");
        if (modality == ModelInputModality.IMAGE) {
          assertEquals("image/png", inlineData.path("mimeType").asText());
          assertEquals("iVBORw0KGgo=", inlineData.path("data").asText());
        } else {
          assertEquals("application/pdf", inlineData.path("mimeType").asText());
          assertEquals("JVBERi0xLjQK", inlineData.path("data").asText());
        }
      }
      case OPENAI_CHAT -> throw new IllegalStateException(
          "OpenAI Chat does not support tool result media");
    }
  }

  private static JsonNode findResponsesToolOutput(JsonNode body) {
    for (JsonNode item : body.path("input")) {
      if ("function_call_output".equals(item.path("type").asText())) {
        assertEquals(TOOL_CALL_ID, item.path("call_id").asText());
        return item.path("output");
      }
    }
    throw new AssertionError("function_call_output item not found");
  }

  private static JsonNode findAnthropicToolResult(JsonNode body) {
    for (JsonNode message : body.path("messages")) {
      for (JsonNode block : message.path("content")) {
        if ("tool_result".equals(block.path("type").asText())) {
          assertEquals(TOOL_CALL_ID, block.path("tool_use_id").asText());
          return block;
        }
      }
    }
    throw new AssertionError("tool_result block not found");
  }

  private static JsonNode findGeminiToolResultContent(JsonNode body) {
    for (JsonNode content : body.path("contents")) {
      for (JsonNode part : content.path("parts")) {
        if (part.has("functionResponse")) {
          return content;
        }
      }
    }
    throw new AssertionError("functionResponse part not found");
  }
}
