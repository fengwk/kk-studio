package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 OpenAI Responses Wire 协议交互、finish reasons 映射与 SSE 事件解析。 */
class OpenAiResponsesWireTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String VALID_PREFIX_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing);
  }

  private ProviderRequest request(
      ModelVariant variant,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        createModel(),
        variant != null ? variant : DEFAULT_VARIANT,
        1024,
        messages != null ? messages : List.of(),
        tools != null ? tools : List.of(),
        cacheControl != null ? cacheControl : ProviderCacheControl.none());
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return request(null, messages, List.of(), null);
  }

  private ProviderRequest request(
      List<ProviderMessage> messages, List<ProviderToolDefinition> tools) {
    return request(null, messages, tools, null);
  }

  /**
   * 对应 upstream OpenAiResponsesFinishReasonTest：验证 completed, max_output_tokens, content_filter 映射。
   */
  @Test
  void test_finishReasonsMappingFromFixture() throws Exception {
    InputStream in = getClass().getResourceAsStream("fixtures/finish-reasons.json");
    assertNotNull(in);
    JsonNode fixture = MAPPER.readTree(in);

    // 1. completed -> COMPLETE
    JsonNode compNode = fixture.get("completed");
    OpenAiResponsesStreamAccumulator acc1 =
        new OpenAiResponsesStreamAccumulator(
            request(List.of()), createDescriptor(), VALID_PREFIX_HASH, e -> {});
    acc1.processEvent(
        MAPPER.createObjectNode().put("type", "response.created").set("response", compNode));
    acc1.processEvent(
        MAPPER.createObjectNode().put("type", "response.completed").set("response", compNode));
    ProviderResponse resp1 = acc1.response();
    assertEquals(GenerationStopReason.COMPLETE, resp1.stopReason());

    // 2. incomplete_max_tokens -> LENGTH
    JsonNode maxTokensNode = fixture.get("incomplete_max_tokens");
    OpenAiResponsesStreamAccumulator acc2 =
        new OpenAiResponsesStreamAccumulator(
            request(List.of()), createDescriptor(), VALID_PREFIX_HASH, e -> {});
    acc2.processEvent(
        MAPPER.createObjectNode().put("type", "response.created").set("response", maxTokensNode));
    acc2.processEvent(
        MAPPER
            .createObjectNode()
            .put("type", "response.incomplete")
            .set("response", maxTokensNode));
    ProviderResponse resp2 = acc2.response();
    assertEquals(GenerationStopReason.LENGTH, resp2.stopReason());

    // 3. incomplete_content_filter -> FILTERED
    JsonNode filterNode = fixture.get("incomplete_content_filter");
    OpenAiResponsesStreamAccumulator acc3 =
        new OpenAiResponsesStreamAccumulator(
            request(List.of()), createDescriptor(), VALID_PREFIX_HASH, e -> {});
    acc3.processEvent(
        MAPPER.createObjectNode().put("type", "response.created").set("response", filterNode));
    acc3.processEvent(
        MAPPER.createObjectNode().put("type", "response.incomplete").set("response", filterNode));
    ProviderResponse resp3 = acc3.response();
    assertEquals(GenerationStopReason.FILTERED, resp3.stopReason());
  }

  /** 对应 upstream OpenAiResponsesStreamingEventParsingTest：验证标准 SSE 文本流解析。 */
  @Test
  void test_streamingEventParsingFromFixture() throws Exception {
    InputStream in = getClass().getResourceAsStream("fixtures/streaming-events.sse");
    assertNotNull(in);
    String sseText = new String(in.readAllBytes(), StandardCharsets.UTF_8);

    List<ProviderStreamEvent> events = new ArrayList<>();
    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(
            request(List.of()), createDescriptor(), VALID_PREFIX_HASH, events::add);

    for (String line : sseText.split("\n")) {
      if (line.startsWith("data: ")) {
        String data = line.substring(6).trim();
        if (!data.isEmpty()) {
          acc.processEvent(MAPPER.readTree(data));
        }
      }
    }

    ProviderResponse resp = acc.response();
    assertEquals("The weather is sunny.", resp.text());
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("resp_stream_test", resp.requestId());
    assertEquals(7, events.size());
  }

  /** 对应 upstream OpenAiResponsesStreamingChatModelPayloadTest：验证输入消息与模型载荷完整构造。 */
  @Test
  void test_streamingChatModelPayload() throws Exception {
    ProviderRequest req =
        new ProviderRequest(
            createModel(),
            new ModelVariant("v1", "medium"),
            100,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("You are helpful."))),
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Tell me a story.")))),
            List.of(),
            ProviderCacheControl.none());

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());

    assertEquals("gpt-5.4-mini", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertEquals(100, root.path("max_output_tokens").asInt());
    assertEquals("medium", root.path("reasoning").path("effort").asText());
    assertEquals("auto", root.path("reasoning").path("summary").asText());
    assertEquals("reasoning.encrypted_content", root.path("include").get(0).asText());

    JsonNode input = root.get("input");
    assertEquals(2, input.size());
    assertEquals("developer", input.get(0).path("role").asText());
    assertEquals("You are helpful.", input.get(0).path("content").get(0).path("text").asText());
    assertEquals("user", input.get(1).path("role").asText());
    assertEquals("Tell me a story.", input.get(1).path("content").get(0).path("text").asText());
  }

  /** 对应 upstream PDF 文档支持与多模态输入验证。 */
  @Test
  void test_pdfDocumentWireEncoding() throws Exception {
    ProviderRequest req =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER,
                    List.of(
                        new ProviderTextBlock("Analyze this PDF:"),
                        new ProviderDocumentBlock(
                            "application/pdf", "https://example.com/spec.pdf")))));

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());

    JsonNode content = root.get("input").get(0).get("content");
    assertEquals(2, content.size());
    assertEquals("input_file", content.get(1).path("type").asText());
    assertEquals("https://example.com/spec.pdf", content.get(1).path("file_url").asText());
  }

  /** 对应 upstream 工具调用请求与工具执行结果回传的 Wire 消息链编码验证。 */
  @Test
  void test_toolCallAndToolResultWireChain() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderToolDefinition tool =
        new ProviderToolDefinition("calculator", "calculate math", "{\"type\":\"object\"}");

    ProviderRequest req =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("What is 2+2?"))),
                new ProviderMessage(
                    ProviderMessageRole.ASSISTANT,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("c_math", "calculator", "{\"expr\":\"2+2\"}")))),
                new ProviderMessage(
                    ProviderMessageRole.TOOL,
                    List.of(
                        new ProviderToolResultBlock(
                            "c_math",
                            "calculator",
                            List.of(new ProviderTextBlock("4")),
                            false,
                            null)))),
            List.of(tool));

    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());

    JsonNode input = root.get("input");
    assertEquals(3, input.size());

    // 0: user message
    assertEquals("message", input.get(0).path("type").asText());
    assertEquals("user", input.get(0).path("role").asText());

    // 1: assistant function call
    assertEquals("function_call", input.get(1).path("type").asText());
    assertEquals("c_math", input.get(1).path("call_id").asText());
    assertEquals("calculator", input.get(1).path("name").asText());
    assertEquals("{\"expr\":\"2+2\"}", input.get(1).path("arguments").asText());

    // 2: tool function_call_output
    assertEquals("function_call_output", input.get(2).path("type").asText());
    assertEquals("c_math", input.get(2).path("call_id").asText());
    assertEquals("4", input.get(2).path("output").asText());
  }

  /** 验证 reasoning effort 为 "none" 时的 Wire 协议编码：仅生成 effort:"none"，省略 summary 与 include。 */
  @Test
  void test_offReasoningWirePayload() throws Exception {
    ProviderRequest req =
        request(
            new ModelVariant("v1", "off"),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Count to three.")))),
            List.of(),
            ProviderCacheControl.none());

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());

    assertEquals("none", root.path("reasoning").path("effort").asText());
    assertFalse(root.path("reasoning").has("summary"));
    assertFalse(root.has("include"));
  }

  /** 验证启用推理与工具调用结合时的 Wire 协议编码：包含 reasoning、include 以及 tools 数组。 */
  @Test
  void test_enabledReasoningWithToolsWirePayload() throws Exception {
    ProviderToolDefinition tool =
        new ProviderToolDefinition("get_time", "get current time", "{\"type\":\"object\"}");
    ProviderRequest req =
        request(
            new ModelVariant("v1", "high"),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("What time is it?")))),
            List.of(tool),
            ProviderCacheControl.none());

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesEncodedRequest enc =
        encoder.encode(req, createDescriptor(), OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(enc.bodyUtf8Bytes());

    assertEquals("high", root.path("reasoning").path("effort").asText());
    assertEquals("auto", root.path("reasoning").path("summary").asText());
    assertEquals("reasoning.encrypted_content", root.path("include").get(0).asText());
    assertTrue(root.has("tools"));
    assertEquals("get_time", root.path("tools").get(0).path("name").asText());
    assertTrue(root.path("tools").get(0).path("strict").asBoolean());
  }

  /**
   * 验证推理请求的完整 wire parity：developer 角色、runtime 派生且稳定的 prompt_cache_key、max_output_tokens 下限与 strict
   * 工具 schema 同时出现在实际编码 JSON 中。
   */
  @Test
  void test_reasoningWireParityPayload() throws Exception {
    ProviderToolDefinition tool =
        new ProviderToolDefinition(
            "get_weather",
            "get weather",
            """
            {"type":"object","properties":{"city":{"type":"string"},"unit":{"type":"string"}},
             "required":["city"],"additionalProperties":false}
            """);
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("You are helpful."))),
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("Weather in Paris?"))));
    ProviderRequest base =
        request(
            new ModelVariant("v1", "medium"), messages, List.of(tool), ProviderCacheControl.none());
    ProviderCacheControl cacheControl =
        new PromptCacheRequestFinalizer(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                UUID.fromString("66666666-6666-6666-6666-666666666666"))
            .apply(
                base,
                PromptCachePolicy.affinityShort(
                    PromptCacheCapability.affinity(Set.of(PromptCacheRetention.SHORT))))
            .cacheControl();
    ProviderRequest req =
        new ProviderRequest(
            base.model(), base.variant(), 16, base.messages(), base.tools(), cacheControl);

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    JsonNode root =
        MAPPER.readTree(
            encoder
                .encode(
                    req,
                    createDescriptor(),
                    new OpenAiResponsesConfig(OpenAiPromptCacheMode.LEGACY))
                .bodyUtf8Bytes());

    // OpenAI Responses 只接受 >= 16 的输出上限，合法冻结预算必须原样编码。
    assertEquals(16, root.path("max_output_tokens").asInt());
    assertEquals("developer", root.get("input").get(0).path("role").asText());
    assertEquals(cacheControl.affinityKey(), root.path("prompt_cache_key").asText());
    assertEquals("in_memory", root.path("prompt_cache_retention").asText());
    assertEquals("medium", root.path("reasoning").path("effort").asText());

    JsonNode parameters = root.get("tools").get(0).path("parameters");
    assertEquals("string", parameters.path("properties").path("city").path("type").asText());
    assertEquals(
        "string",
        parameters.path("properties").path("unit").path("anyOf").get(0).path("type").asText());
    assertEquals(
        "null",
        parameters.path("properties").path("unit").path("anyOf").get(1).path("type").asText());
  }
}
