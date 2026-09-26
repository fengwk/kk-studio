package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** 验证 native protocolOptions 合并语义：非所有权官方顶层字段无损透传、所有权字段冲突显式失败、tools 与 output_config 按官方语义合并。 */
class AnthropicProtocolOptionsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  /** 除 runtime 所有权字段外的官方顶层选项集合；{@code thinking}/{@code output_config} 在 effort 未声明时也必须原样保留。 */
  private static final String NATIVE_OFFICIAL_OPTIONS =
      """
      {
        "temperature": 0.7,
        "top_p": 0.9,
        "top_k": 4,
        "stop_sequences": ["STOP"],
        "metadata": {"user_id": "user-1"},
        "service_tier": "standard_only",
        "thinking": {"type": "enabled", "budget_tokens": 2048},
        "output_config": {"effort": "low", "format": {"type": "json_schema", "schema": {"type": "object"}}},
        "tool_choice": {"type": "auto"},
        "mcp_servers": [{"type": "url", "url": "https://mcp.example.com", "name": "mcp"}],
        "container": "container-1"
      }
      """;

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  /**
   * 测试意图：非 runtime 所有权官方顶层字段（含 effort 未声明时的 thinking/output_config）必须无损进入 wire，runtime 事实仍由 runtime
   * 写入。
   */
  @Test
  void passesThroughNonOwnedOfficialTopLevelOptions() throws IOException {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant(null, NATIVE_OFFICIAL_OPTIONS),
            1024,
            List.of(userMsg()),
            List.of());

    JsonNode root = wire(encoder.encode(request, descriptor));

    // 非所有权官方顶层字段逐个原样进入 wire
    assertEquals(0.7, root.path("temperature").asDouble(), 1e-9);
    assertEquals(0.9, root.path("top_p").asDouble(), 1e-9);
    assertEquals(4, root.path("top_k").asInt());
    assertEquals("STOP", root.path("stop_sequences").get(0).asText());
    assertEquals("user-1", root.path("metadata").path("user_id").asText());
    assertEquals("standard_only", root.path("service_tier").asText());
    assertEquals("auto", root.path("tool_choice").path("type").asText());
    assertEquals("https://mcp.example.com", root.path("mcp_servers").get(0).path("url").asText());
    assertEquals("container-1", root.path("container").asText());

    // effort 未声明：native thinking 与 output_config（含 effort）都不得被 runtime 改写
    assertEquals("enabled", root.path("thinking").path("type").asText());
    assertEquals(2048, root.path("thinking").path("budget_tokens").asInt());
    assertEquals("low", root.path("output_config").path("effort").asText());
    assertEquals("json_schema", root.path("output_config").path("format").path("type").asText());

    // runtime 事实仍由 runtime 写入
    assertEquals("claude-3-5-sonnet", root.path("model").asText());
    assertEquals(1024, root.path("max_tokens").asInt());
    assertTrue(root.path("stream").asBoolean());
    assertFalse(root.has("tools"));
  }

  /** 测试意图：tools 合并保留 native server tool 并追加 runtime function tool，且合并结果参与冻结前缀哈希。 */
  @Test
  void mergesNativeServerToolsBeforeRuntimeFunctionTools() throws IOException {
    ProviderToolDefinition runtimeTool =
        new ProviderToolDefinition("calc", "calculate", "{\"type\":\"object\"}");

    ProviderRequest withNativeTools =
        request(
            reasoningModel(),
            variant(
                null,
                "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":3}]}"),
            1024,
            List.of(userMsg()),
            List.of(runtimeTool));

    AnthropicEncodedRequest encoded = encoder.encode(withNativeTools, descriptor);
    JsonNode tools = wire(encoded).path("tools");

    // native server tool 在前、runtime function tool 在后，native 条目逐字段保留
    assertEquals(2, tools.size());
    assertEquals("web_search_20250305", tools.get(0).path("type").asText());
    assertEquals("web_search", tools.get(0).path("name").asText());
    assertEquals(3, tools.get(0).path("max_uses").asInt());
    assertEquals("calc", tools.get(1).path("name").asText());
    assertEquals("object", tools.get(1).path("input_schema").path("type").asText());

    // 合并结果参与 prefix hash：与不含 native tools 的同一请求必须是不同的冻结前缀
    ProviderRequest withoutNativeTools =
        request(
            reasoningModel(), variant(null, "{}"), 1024, List.of(userMsg()), List.of(runtimeTool));
    assertNotEquals(
        encoder.encode(withoutNativeTools, descriptor).sourcePrefixHash(),
        encoded.sourcePrefixHash());
  }

  /** 测试意图：只声明 native server tools 时不得被 runtime 空工具列表抹掉。 */
  @Test
  void keepsNativeToolsWhenNoRuntimeToolsDeclared() throws IOException {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant(
                null, "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}"),
            1024,
            List.of(userMsg()),
            List.of());

    JsonNode tools = wire(encoder.encode(request, descriptor)).path("tools");

    assertEquals(1, tools.size());
    assertEquals("web_search_20250305", tools.get(0).path("type").asText());
  }

  /** 测试意图：native tools 不是 array 时显式失败，不做静默兼容。 */
  @Test
  void rejectsNonArrayNativeTools() {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant(null, "{\"tools\":{\"name\":\"calc\"}}"),
            1024,
            List.of(userMsg()),
            List.of());

    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals("protocolOptions tools must be a JSON array", error.getMessage());
  }

  /** 测试意图：原生工具仅限带名称的 server 工具；未绑定的客户端工具和同名 runtime 工具不得下发。 */
  @Test
  void rejectsUnboundNativeTools() {
    for (String option :
        List.of(
            "{\"tools\":[null]}",
            "{\"tools\":[{}]}",
            "{\"tools\":[{\"name\":\"secret\",\"input_schema\":{}}]}",
            "{\"tools\":[{\"type\":\"custom\",\"name\":\"secret\"}]}",
            "{\"tools\":[{\"type\":\"computer_20250124\",\"name\":\"secret\"}]}",
            "{\"tools\":[{\"type\":\"bash_20250124\",\"name\":\"secret\"}]}",
            "{\"tools\":[{\"type\":\"text_editor_20250124\",\"name\":\"secret\"}]}",
            "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\" \"}]}",
            "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"calc\"}]}")) {
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () ->
                  encoder.encode(
                      request(
                          reasoningModel(),
                          variant(null, option),
                          1024,
                          List.of(userMsg()),
                          List.of(
                              new ProviderToolDefinition("calc", "calc", "{\"type\":\"object\"}"))),
                      descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertFalse(error.getMessage().contains("secret"));
    }
  }

  /** 测试意图：TOOLS cache marker 必须打在最终合并工具数组的最后一个条目上，native 条目保持原样。 */
  @Test
  void placesToolCacheMarkerOnFinalMergedToolsArray() throws IOException {
    ProviderToolDefinition runtimeTool =
        new ProviderToolDefinition("calc", "calculate", "{\"type\":\"object\"}");
    ProviderRequest request =
        request(
            reasoningModel(),
            variant(
                null, "{\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}"),
            1024,
            List.of(userMsg()),
            List.of(runtimeTool),
            new ProviderCacheControl(
                PromptCacheRetention.SHORT, "aff", Set.of(PromptCacheBreakpoint.TOOLS)));

    JsonNode tools = wire(encoder.encode(request, descriptor)).path("tools");

    // cache marker 必须落在最终合并结果的最后一个 tool（runtime 工具）上，native server tool 保持原样
    assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asText());
    assertFalse(tools.get(0).has("cache_control"));
  }

  /** 测试意图：runtime effort 与 native output_config 官方子字段（format）合并共处，thinking 由 runtime 独占。 */
  @Test
  void mergesRuntimeEffortIntoNativeOutputConfig() throws IOException {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant(
                "high",
                "{\"output_config\":{\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}}}"),
            1024,
            List.of(userMsg()),
            List.of());

    JsonNode root = wire(encoder.encode(request, descriptor));

    assertEquals("adaptive", root.path("thinking").path("type").asText());
    assertEquals("high", root.path("output_config").path("effort").asText());
    assertEquals("json_schema", root.path("output_config").path("format").path("type").asText());
    assertEquals(
        "object", root.path("output_config").path("format").path("schema").path("type").asText());
  }

  /** 测试意图：BUDGET 模式 runtime 不写 effort，native output_config 子字段保留且不得凭空生成 effort。 */
  @Test
  void preservesNativeOutputConfigSubFieldsInBudgetMode() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    ProviderRequest request =
        request(
            reasoningModel(),
            variant("medium", "{\"output_config\":{\"format\":{\"type\":\"json_schema\"}}}"),
            32768,
            List.of(userMsg()),
            List.of());

    JsonNode root = wire(budgetEncoder.encode(request, descriptor));

    // BUDGET 模式 runtime 不写 effort：native output_config 子字段原样保留且不得凭空出现 effort
    assertEquals("enabled", root.path("thinking").path("type").asText());
    assertEquals(8192, root.path("thinking").path("budget_tokens").asInt());
    assertEquals("json_schema", root.path("output_config").path("format").path("type").asText());
    assertFalse(root.path("output_config").has("effort"));
  }

  /** 测试意图：reasoningEffort 非 null 时 runtime 独占 thinking，native thinking 必须显式失败。 */
  @Test
  void rejectsNativeThinkingWhenRuntimeDeclaresReasoning() {
    // ADAPTIVE 与显式 off 都必须拒绝 native thinking：runtime 一旦要独占该字段就不得静默覆盖
    for (String effort : List.of("high", "off")) {
      ProviderRequest request =
          request(
              reasoningModel(),
              variant(effort, "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}}"),
              1024,
              List.of(userMsg()),
              List.of());

      ProviderException error =
          assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertEquals("protocolOptions must not override runtime field: thinking", error.getMessage());
      assertFalse(error.getMessage().contains("2048"));
    }
  }

  /** 测试意图：native output_config.effort 与 runtime 要写的 effort 冲突时显式失败且不回显 value。 */
  @Test
  void rejectsNativeOutputConfigEffortWhenRuntimeWritesEffort() {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant("high", "{\"output_config\":{\"effort\":\"s3cret-effort\"}}"),
            1024,
            List.of(userMsg()),
            List.of());

    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(
        "protocolOptions must not override runtime field: output_config.effort",
        error.getMessage());
    assertFalse(error.getMessage().contains("s3cret-effort"));
  }

  /** 测试意图：native output_config 非 object 时不得被 runtime 当作可合并对象。 */
  @Test
  void rejectsNonObjectNativeOutputConfigWhenRuntimeWritesEffort() {
    ProviderRequest request =
        request(
            reasoningModel(),
            variant("high", "{\"output_config\":[]}"),
            1024,
            List.of(userMsg()),
            List.of());

    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals("protocolOptions output_config must be a JSON object", error.getMessage());
  }

  /** runtime 所有权字段冲突逐字段验证：必须显式 INVALID_REQUEST，且错误只报字段名、绝不回显注入的 value。 */
  @ParameterizedTest
  @MethodSource("runtimeOwnedConflicts")
  void rejectsNativeOptionsOverridingRuntimeFacts(String field, String optionsJson) {
    ProviderRequest request =
        request(reasoningModel(), variant(null, optionsJson), 1024, List.of(userMsg()), List.of());

    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals("protocolOptions must not override runtime field: " + field, error.getMessage());
    assertFalse(error.getMessage().contains("s3cret"));
    assertFalse(error.getMessage().contains("424242"));
  }

  private static Stream<Arguments> runtimeOwnedConflicts() {
    return Stream.of(
        Arguments.of("model", "{\"model\":\"claude-s3cret-model\"}"),
        Arguments.of("max_tokens", "{\"max_tokens\":424242}"),
        Arguments.of("stream", "{\"stream\":false}"),
        Arguments.of("messages", "{\"messages\":[{\"role\":\"user\",\"content\":\"s3cret\"}]}"),
        Arguments.of("system", "{\"system\":\"s3cret system\"}"),
        Arguments.of("cache_control", "{\"cache_control\":{\"type\":\"s3cret-ephemeral\"}}"));
  }

  private static JsonNode wire(AnthropicEncodedRequest encoded) throws IOException {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private static ProviderRequest request(
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools) {
    return request(model, variant, outputTokens, messages, tools, ProviderCacheControl.none());
  }

  private static ProviderRequest request(
      ModelDescriptor model,
      ModelVariant variant,
      int outputTokens,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools,
      ProviderCacheControl cacheControl) {
    return new ProviderRequest(
        model, variant, outputTokens, SYSTEM_INSTRUCTION, messages, tools, cacheControl);
  }

  private static ModelVariant variant(String reasoningEffort, String protocolOptionsJson) {
    return new ModelVariant(
        "native", reasoningEffort, new ProviderProtocolOptions(protocolOptionsJson));
  }

  private static ModelDescriptor reasoningModel() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-5-sonnet",
        "claude-3-5-sonnet",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing());
  }

  private static ProviderMessage userMsg() {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")));
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
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
  }
}
