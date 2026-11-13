package fun.fengwk.kkstudio.harness.builtin.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 四协议历史保真的生产路径夹具：assistant JSON 诊断的合法文本投影，以及 bindings/environment 变化后的跨协议语义回退。
 *
 * <p>全部通过四个公开 adapter 的 {@link ProviderAdapter#encodeRequestBody} 观察最终 wire；数据为合成标记，不触碰网络
 * （transport 仅用于构造 adapter），也不回显任何真实历史或凭据。
 */
class AssistantHistoryFallbackWireTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String JSON_DIAGNOSTIC = "{\"synthetic\":\"tool_call_diagnostic\"}";
  private static final String TEXT = "synthetic assistant answer";
  private static final String API_KEY = "synthetic-api-key";
  private static final ModelCallTimeoutPolicy TIMEOUT_POLICY =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10));

  private HttpClient client;
  private ExecutorService workers;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setUp() {
    client = HttpClient.newHttpClient();
    workers = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDown() {
    workers.shutdownNow();
    scheduler.shutdownNow();
  }

  /**
   * 意图：assistant 的 {@code ProviderJsonBlock} 诊断在四个协议都必须投影为合法文本，而不是 INVALID_REQUEST； 且不得被伪装成原生
   * reasoning/thinking 结构。
   */
  @Test
  void assistantJsonDiagnosticProjectsAsLegalTextAcrossFourProtocols() throws Exception {
    List<ProviderContentBlock> contents = List.of(new ProviderJsonBlock(JSON_DIAGNOSTIC));

    JsonNode chatWire =
        encode(chatAdapter(), assistantRequest(chatModel(), contents), chatDescriptor());
    JsonNode chatAssistant = itemWithRole(chatWire.path("messages"), "assistant");
    assertTrue(chatAssistant.path("content").asString("").contains(JSON_DIAGNOSTIC));

    JsonNode responsesInput =
        encode(
                responsesAdapter(),
                assistantRequest(responsesModel(), contents),
                responsesDescriptor())
            .path("input");
    JsonNode responsesText = itemOfType(responsesInput, "message").path("content").get(0);
    assertEquals("output_text", responsesText.path("type").asString(""));
    assertTrue(responsesText.path("text").asString("").contains(JSON_DIAGNOSTIC));

    JsonNode geminiParts =
        itemWithRole(
                encode(
                        geminiAdapter(),
                        assistantRequest(geminiModel(), contents),
                        geminiDescriptor())
                    .path("contents"),
                "model")
            .path("parts");
    assertEquals(JSON_DIAGNOSTIC, geminiParts.get(0).path("text").asString(""));
    assertFalse(geminiParts.get(0).has("thought"), "JSON diagnostic must not be forged as thought");

    JsonNode anthropicContent =
        itemWithRole(
                encode(
                        anthropicAdapter(),
                        assistantRequest(anthropicModel(), contents),
                        anthropicDescriptor())
                    .path("messages"),
                "assistant")
            .path("content");
    assertEquals("text", anthropicContent.get(0).path("type").asString(""));
    assertEquals(JSON_DIAGNOSTIC, anthropicContent.get(0).path("text").asString(""));
  }

  /**
   * Historical tool calls remain native on the wire even when absent from the current binding set.
   */
  @Test
  void projectedHistoricalCallEncodesAcrossFourProtocols() throws Exception {
    AgentMessage assistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent(TEXT),
                new ToolCallMessageContent("call-native", "lookup", "lookup", "{}", null, null),
                new ToolCallMessageContent(
                    "call-legacy", "bash", "bash", "{\"cmd\":\"ls\"}", "run bash", "dev")));
    AgentMessage nativeResult =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call-native",
                    "lookup",
                    "lookup",
                    List.of(new TextMessageContent("native output")),
                    false,
                    "{}")));

    // 当前 request 无 bash binding，旧调用及其 arguments 仍须保留为原生调用。
    List<ProviderMessage> projected =
        new ProviderMessageProjector()
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(assistant),
                    ProviderMessageProjector.ProjectedMessage.of(nativeResult)));

    assertEquals(TEXT, ((ProviderTextBlock) projected.get(0).contents().get(0)).text());
    assertEquals(
        "call-native",
        ((ProviderToolCallBlock) projected.get(0).contents().get(1)).toolCall().id());

    assertProjectedWireContainsHistoricalCalls(
        encode(chatAdapter(), request(chatModel(), projected), chatDescriptor()));
    assertProjectedWireContainsHistoricalCalls(
        encode(responsesAdapter(), request(responsesModel(), projected), responsesDescriptor()));
    assertProjectedWireContainsHistoricalCalls(
        encode(geminiAdapter(), request(geminiModel(), projected), geminiDescriptor()));
    assertProjectedWireContainsHistoricalCalls(
        encode(anthropicAdapter(), request(anthropicModel(), projected), anthropicDescriptor()));
  }

  private static void assertProjectedWireContainsHistoricalCalls(JsonNode wire) {
    String text = wire.toString();
    assertTrue(text.contains("lookup"), "native call must survive into the wire: " + text);
    assertTrue(text.contains(TEXT), "assistant text must survive into the wire");
    assertTrue(text.contains("call-legacy"), "historical tool-call identity must be preserved");
    assertTrue(text.contains("bash"), "historical tool name must be preserved");
    assertTrue(text.contains("ls"), "historical tool arguments must be preserved");
  }

  private JdkHttpSseTransport transport() {
    return new JdkHttpSseTransport(client, scheduler);
  }

  private ProviderAdapter chatAdapter() {
    return new OpenAiChatProviderAdapter(transport(), API_KEY);
  }

  private ProviderAdapter responsesAdapter() {
    return new OpenAiResponsesProviderAdapter(transport(), API_KEY);
  }

  private ProviderAdapter anthropicAdapter() {
    return new AnthropicProviderAdapter(transport(), API_KEY);
  }

  private ProviderAdapter geminiAdapter() {
    return new GeminiProviderAdapter(transport(), API_KEY);
  }

  private static ModelDescriptor chatModel() {
    return model("openai_chat_synthetic", "gpt-synthetic");
  }

  private static ModelDescriptor responsesModel() {
    return model("responses_synthetic", "gpt-synthetic");
  }

  private static ModelDescriptor anthropicModel() {
    return model("anthropic_synthetic", "claude-synthetic");
  }

  private static ModelDescriptor geminiModel() {
    return model("gemini_synthetic", "gemini-synthetic");
  }

  private static ModelDescriptor model(String providerName, String modelId) {
    return new ModelDescriptor(
        providerName, modelId, modelId, Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ProviderDescriptor descriptor(ProviderType type, String providerName) {
    return new ProviderDescriptor(providerName, type, "https://example.invalid/v1", TIMEOUT_POLICY);
  }

  private static ProviderDescriptor chatDescriptor() {
    return descriptor(ProviderType.OPENAI, chatModel().providerName());
  }

  private static ProviderDescriptor responsesDescriptor() {
    return descriptor(ProviderType.OPENAI_RESPONSES, responsesModel().providerName());
  }

  private static ProviderDescriptor anthropicDescriptor() {
    return descriptor(ProviderType.ANTHROPIC, anthropicModel().providerName());
  }

  private static ProviderDescriptor geminiDescriptor() {
    return descriptor(ProviderType.GOOGLE, geminiModel().providerName());
  }

  private static ProviderRequest assistantRequest(
      ModelDescriptor model, List<ProviderContentBlock> contents) {
    return new ProviderRequest(
        model,
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        "Synthetic system instruction.",
        List.of(new ProviderMessage(ProviderMessageRole.ASSISTANT, contents)),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderRequest request(ModelDescriptor model, List<ProviderMessage> messages) {
    return new ProviderRequest(
        model,
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        "Synthetic system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static JsonNode encode(
      ProviderAdapter adapter, ProviderRequest request, ProviderDescriptor descriptor)
      throws Exception {
    return MAPPER.readTree(adapter.encodeRequestBody(request, descriptor));
  }

  private static JsonNode itemOfType(JsonNode array, String type) {
    for (JsonNode item : array) {
      if (type.equals(item.path("type").asString(""))) {
        return item;
      }
    }
    throw new AssertionError("no item of type " + type + " in " + array);
  }

  private static JsonNode itemWithRole(JsonNode array, String role) {
    for (JsonNode item : array) {
      if (role.equals(item.path("role").asString(""))) {
        return item;
      }
    }
    throw new AssertionError("no item with role " + role + " in " + array);
  }
}
