package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 测试意图：验证 variant native protocolOptions 在 OpenAI Chat 请求体上的合并契约——官方顶层能力无损透传、运行时所有权字段冲突拒绝、 {@code
 * stream_options} / {@code tools} 合并与 prefix hash 一致，以及 reasoning 字段在 native 与 runtime 之间的归属切换。
 */
class OpenAiChatProtocolOptionsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OpenAiChatRequestEncoder encoder;
  private ProviderDescriptor descriptor;
  private ModelDescriptor reasoningModel;
  private ModelDescriptor plainModel;

  @BeforeEach
  void setUp() {
    encoder = new OpenAiChatRequestEncoder();
    descriptor =
        new ProviderDescriptor(
            "openai",
            ProviderType.OPENAI,
            "https://api.openai.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "standard",
            "tier1",
            BigDecimal.ONE,
            "v1",
            new BigDecimal("2.50"),
            new BigDecimal("10.00"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("1.25"),
            new BigDecimal("10.00"));
    reasoningModel =
        new ModelDescriptor(
            "openai",
            "gpt-4o-reasoning",
            "gpt-4o-reasoning",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
    plainModel =
        new ModelDescriptor(
            "openai", "gpt-4o", "gpt-4o", Set.of(ModelInputModality.TEXT), true, false, pricing);
  }

  @Test
  @DisplayName("structured output / audio / modalities 等官方顶层字段与运行时事实并存")
  void keepsOfficialTopLevelOptionsAlongsideRuntimeFacts() throws Exception {
    ModelVariant variant =
        nativeVariant(
            """
            {
              "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "answer", "schema": {"type": "object"}}
              },
              "modalities": ["text", "audio"],
              "audio": {"voice": "alloy", "format": "wav"},
              "prediction": {"type": "content", "content": "expected"},
              "logprobs": true,
              "top_logprobs": 3,
              "web_search_options": {"search_context_size": "low"},
              "service_tier": "flex",
              "metadata": {"tenant": "t1"},
              "parallel_tool_calls": false,
              "seed": 7,
              "temperature": 0.25,
              "top_p": 0.9,
              "frequency_penalty": -0.5,
              "presence_penalty": 0.5,
              "stop": ["END"],
              "user": "u-1",
              "n": 1
            }
            """);

    JsonNode root = encode(reasoningModel, variant, List.of());

    assertTrue(root.path("response_format").path("json_schema").path("schema").isObject());
    assertEquals(2, root.path("modalities").size());
    assertEquals("alloy", root.path("audio").path("voice").asText());
    assertEquals("expected", root.path("prediction").path("content").asText());
    assertTrue(root.path("logprobs").asBoolean());
    assertEquals(3, root.path("top_logprobs").asInt());
    assertEquals("low", root.path("web_search_options").path("search_context_size").asText());
    assertEquals("flex", root.path("service_tier").asText());
    assertEquals("t1", root.path("metadata").path("tenant").asText());
    assertFalse(root.path("parallel_tool_calls").asBoolean());
    assertEquals(7, root.path("seed").asInt());
    assertEquals(0.25, root.path("temperature").asDouble());
    assertEquals(0.9, root.path("top_p").asDouble());
    assertEquals(-0.5, root.path("frequency_penalty").asDouble());
    assertEquals(0.5, root.path("presence_penalty").asDouble());
    assertEquals("END", root.path("stop").get(0).asText());
    assertEquals("u-1", root.path("user").asText());
    assertEquals(1, root.path("n").asInt());

    // 运行时事实不被 native 影响
    assertEquals("gpt-4o-reasoning", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertEquals(1024, root.path("max_tokens").asInt());
    assertFalse(root.has("max_completion_tokens"));
    assertTrue(root.path("stream_options").path("include_usage").asBoolean());
    assertEquals("system", root.path("messages").get(0).path("role").asText());
  }

  /** 测试意图：原始小数/指数经 protocolOptions 规范化为整数 1 后，单候选请求仍可执行；非 1 小数另有拒绝用例。 */
  @Test
  void acceptsNormalizedSingleCandidateNumbers() throws Exception {
    for (String numeric : List.of("1.0", "1e0")) {
      ModelVariant variant = nativeVariant("{\"n\":" + numeric + "}");
      assertEquals("{\"n\":1}", variant.protocolOptions().canonicalJson());
      JsonNode root = encode(plainModel, variant, List.of());
      assertEquals(1, root.path("n").intValue());
      assertTrue(root.path("stream").booleanValue());
    }
  }

  @ParameterizedTest(name = "runtime-owned 字段 {0} 冲突拒绝且不回显 native 值")
  @ValueSource(
      strings = {
        "model",
        "messages",
        "stream",
        "max_tokens",
        "max_completion_tokens",
        "prompt_cache_key",
        "prompt_cache_retention",
        "prompt_cache_options"
      })
  void rejectsRuntimeOwnedNativeFields(String field) {
    ModelVariant variant = nativeVariant("{\"" + field + "\":\"sk-native-secret-value\"}");

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                encoder.encode(
                    request(reasoningModel, variant, List.of()),
                    descriptor,
                    OpenAiChatConfiguration.defaults()));

    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertTrue(error.getMessage().contains(field));
    assertFalse(error.getMessage().contains("sk-native-secret-value"));
  }

  @Test
  @DisplayName("stream_options 保留 native 子字段，include_usage 由运行时独占")
  void mergesStreamOptionsWithRuntimeIncludeUsage() throws Exception {
    JsonNode merged =
        encode(
            reasoningModel,
            nativeVariant("{\"stream_options\":{\"continuous_usage_stats\":true}}"),
            List.of());
    assertTrue(merged.path("stream_options").path("continuous_usage_stats").asBoolean());
    assertTrue(merged.path("stream_options").path("include_usage").asBoolean());

    // runtime 配置仍然决定 include_usage 的最终值
    OpenAiChatConfiguration withoutUsage =
        new OpenAiChatConfiguration(false, true, OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC);
    JsonNode configured =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        reasoningModel,
                        nativeVariant("{\"stream_options\":{\"continuous_usage_stats\":true}}"),
                        List.of()),
                    descriptor,
                    withoutUsage)
                .bodyUtf8Bytes());
    assertFalse(configured.path("stream_options").path("include_usage").asBoolean());
    assertTrue(configured.path("stream_options").path("continuous_usage_stats").asBoolean());

    // include_usage 是 runtime-owned：native 声明即冲突
    ProviderException conflict =
        assertThrows(
            ProviderException.class,
            () ->
                encode(
                    reasoningModel,
                    nativeVariant("{\"stream_options\":{\"include_usage\":false}}"),
                    List.of()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, conflict.kind());

    // 非 object 形态拒绝
    ProviderException illegalShape =
        assertThrows(
            ProviderException.class,
            () -> encode(reasoningModel, nativeVariant("{\"stream_options\":[]}"), List.of()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, illegalShape.kind());
  }

  @Test
  @DisplayName("空 native tools 与运行时 function tools 合并为同一数组并共同参与 prefix hash")
  void mergesNativeAndRuntimeToolsIntoHashedArray() throws Exception {
    ModelVariant variant = nativeVariant("""
            {"tools":[]}
            """);
    ProviderToolDefinition runtimeTool =
        new ProviderToolDefinition("runtime_calc", "calc", "{\"type\":\"object\"}");

    OpenAiChatEncodedRequest encoded =
        encoder.encode(
            request(reasoningModel, variant, List.of(runtimeTool)),
            descriptor,
            OpenAiChatConfiguration.defaults());
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    ArrayNode tools = (ArrayNode) root.path("tools");
    assertEquals(1, tools.size());
    assertEquals("runtime_calc", tools.get(0).path("function").path("name").asText());

    // prefix hash 覆盖最终 wire 数组（runtime 工具）
    assertEquals(
        OpenAiChatPrefixHasher.calculateHash(tools, (ArrayNode) root.path("messages")),
        encoded.sourcePrefixHash());

    // 仅空 native tools 时保留空声明
    OpenAiChatEncodedRequest nativeOnlyEncoded =
        encoder.encode(
            request(reasoningModel, variant, List.of()),
            descriptor,
            OpenAiChatConfiguration.defaults());
    assertEquals(0, MAPPER.readTree(nativeOnlyEncoded.bodyUtf8Bytes()).path("tools").size());
    String plainHash =
        encoder
            .encode(
                request(reasoningModel, new ModelVariant("native"), List.of(runtimeTool)),
                descriptor,
                OpenAiChatConfiguration.defaults())
            .sourcePrefixHash();
    assertNotEquals(plainHash, nativeOnlyEncoded.sourcePrefixHash());

    // 非 array 形态拒绝
    ProviderException illegalShape =
        assertThrows(
            ProviderException.class,
            () -> encode(reasoningModel, nativeVariant("{\"tools\":{}}"), List.of()));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, illegalShape.kind());
  }

  /** 测试意图：多候选和原生客户端工具在编码阶段失败，不允许以无法闭环的工具调用进入流。 */
  @Test
  void rejectsUnsupportedExecutionOptions() {
    for (String options :
        List.of(
            "{\"n\":null}",
            "{\"n\":true}",
            "{\"n\":1.5}",
            "{\"n\":\"1\"}",
            "{\"n\":0}",
            "{\"n\":2}",
            "{\"n\":99999999999999999999999}",
            "{\"tools\":[null]}",
            "{\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"secret\"}}]}",
            "{\"functions\":[]}",
            "{\"function_call\":\"none\"}")) {
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () -> encode(plainModel, nativeVariant(options), List.of()),
              options);
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertFalse(error.getMessage().contains("secret"));
    }
  }

  @Test
  @DisplayName("reasoning 字段归属：variant 未声明 effort 时归 native，声明后由 runtime 独占")
  void switchesReasoningFieldOwnership() throws Exception {
    // variant 未声明 effort：native 的 reasoning_effort 原样保留
    JsonNode nativeEffort =
        encode(reasoningModel, nativeVariant("{\"reasoning_effort\":\"xhigh\"}"), List.of());
    assertEquals("xhigh", nativeEffort.path("reasoning_effort").asText());

    // variant 未声明 effort：native 的 thinking 开关与预算原样保留
    JsonNode nativeThinking =
        encode(
            reasoningModel,
            nativeVariant("{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4096}}"),
            List.of());
    assertEquals("enabled", nativeThinking.path("thinking").path("type").asText());
    assertEquals(4096, nativeThinking.path("thinking").path("budget_tokens").asInt());

    // 非 reasoning 模型 + variant 未声明 effort：native 字段仍属厂商原生事实
    JsonNode plainNative =
        encode(plainModel, nativeVariant("{\"reasoning_effort\":\"high\"}"), List.of());
    assertEquals("high", plainNative.path("reasoning_effort").asText());

    // variant 声明 effort 后 reasoning_effort / thinking 由 runtime 独占：native 声明即冲突
    for (String field : List.of("reasoning_effort", "thinking")) {
      ModelVariant declaredEffort = nativeVariant("{\"" + field + "\":\"legacy\"}", "high");
      ProviderException conflict =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      request(reasoningModel, declaredEffort, List.of()),
                      descriptor,
                      OpenAiChatConfiguration.defaults()));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, conflict.kind());
      assertFalse(conflict.getMessage().contains("legacy"));
    }

    // 声明 effort 后仍按现有 STANDARD / DEEPSEEK 语义写出运行时字段
    JsonNode standard =
        MAPPER.readTree(
            encoder
                .encode(
                    request(reasoningModel, nativeVariant("{\"seed\":1}", "high"), List.of()),
                    descriptor,
                    OpenAiChatConfiguration.defaults())
                .bodyUtf8Bytes());
    assertEquals("high", standard.path("reasoning_effort").asText());

    JsonNode deepseek =
        MAPPER.readTree(
            encoder
                .encode(
                    request(reasoningModel, nativeVariant("{\"seed\":1}", "off"), List.of()),
                    descriptor,
                    new OpenAiChatConfiguration(
                        true,
                        true,
                        OpenAiChatConfiguration.PromptCacheMode.AUTOMATIC,
                        OpenAiChatConfiguration.ThinkingFormat.DEEPSEEK))
                .bodyUtf8Bytes());
    assertEquals("disabled", deepseek.path("thinking").path("type").asText());
    assertFalse(deepseek.has("reasoning_effort"));
  }

  private static ModelVariant nativeVariant(String optionsJson) {
    return nativeVariant(optionsJson, null);
  }

  private static ModelVariant nativeVariant(String optionsJson, String reasoningEffort) {
    return new ModelVariant("native", reasoningEffort, new ProviderProtocolOptions(optionsJson));
  }

  private static ProviderRequest request(
      ModelDescriptor model, ModelVariant variant, List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        model,
        variant,
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")))),
        tools,
        ProviderCacheControl.none());
  }

  private JsonNode encode(
      ModelDescriptor model, ModelVariant variant, List<ProviderToolDefinition> tools)
      throws Exception {
    return MAPPER.readTree(
        encoder
            .encode(request(model, variant, tools), descriptor, OpenAiChatConfiguration.defaults())
            .bodyUtf8Bytes());
  }
}
