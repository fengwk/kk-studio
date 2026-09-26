package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 variant 原生协议选项（{@code protocolOptions}）与 runtime 事实的合并语义。
 *
 * <p>覆盖三类行为：非 runtime 所有权字段无损透传、runtime 所有权字段冲突明确拒绝且不回显值、 {@code tools}/{@code reasoning}/ {@code
 * include} 的嵌套合并保留原生子字段。
 */
class OpenAiResponsesNativeProtocolOptionsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing());
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
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
  }

  private static ModelVariant variant(String id, String effort, String protocolOptionsJson) {
    return new ModelVariant(id, effort, new ProviderProtocolOptions(protocolOptionsJson));
  }

  private ProviderRequest request(
      ModelVariant variant, List<ProviderMessage> messages, List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        createModel(),
        variant,
        1024,
        "Test system instruction.",
        messages,
        tools,
        ProviderCacheControl.none());
  }

  private JsonNode encode(ProviderRequest request) throws Exception {
    return MAPPER.readTree(
        encoder
            .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
            .bodyUtf8Bytes());
  }

  private static List<ProviderMessage> userMessage() {
    return List.of(
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi"))));
  }

  private static ProviderToolDefinition functionTool(String name) {
    return new ProviderToolDefinition(
        name, "desc", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
  }

  /**
   * 测试意图：非 runtime 所有权官方字段（text/truncation/background/conversation 之外的请求事实、metadata、prompt 等）原样上
   * wire， 既不丢字段也不改语义，并且不参与冻结前缀哈希。
   */
  @Test
  void test_nonOwnedOfficialFieldsPassedThroughLosslessly() throws Exception {
    String options =
        """
        {
          "text": {"format": {"type": "json_schema", "name": "answer",
                              "schema": {"type": "object", "properties": {"a": {"type": "string"}}}}},
          "truncation": "auto",
          "background": false,
          "parallel_tool_calls": false,
          "service_tier": "flex",
          "metadata": {"trace": "t-1", "nested": {"level": 2}},
          "safety_identifier": "sid-1",
          "prompt": {"id": "pmpt_1", "version": "3", "variables": {"k": "v"}},
          "top_logprobs": 3
        }
        """;
    ModelVariant withOptions = variant("native", "low", options);
    JsonNode root = encode(request(withOptions, userMessage(), List.of()));
    JsonNode nativeOptions = MAPPER.readTree(options);
    nativeOptions
        .fields()
        .forEachRemaining(
            entry -> assertEquals(entry.getValue(), root.get(entry.getKey()), entry.getKey()));

    // runtime 事实仍然唯一决定所有权字段
    assertEquals("gpt-5.4-mini", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertFalse(root.path("store").asBoolean());
    assertEquals("Test system instruction.", root.path("instructions").asText());
    assertEquals(1024, root.path("max_output_tokens").asInt());
    assertEquals("low", root.path("reasoning").path("effort").asText());
    // 采样参数仍绝不发送
    assertFalse(root.has("temperature"));
    assertFalse(root.has("top_p"));

    // 非所有权字段不进入冻结前缀哈希：同一变体声明与否前缀哈希一致，replay 不受影响
    JsonNode withoutOptions =
        encode(request(new ModelVariant("plain", "low"), userMessage(), List.of()));
    assertEquals(
        encoder
            .encode(
                request(new ModelVariant("plain", "low"), userMessage(), List.of()),
                createDescriptor(),
                OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash(),
        encoder
            .encode(
                request(withOptions, userMessage(), List.of()),
                createDescriptor(),
                OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash());
    assertFalse(withoutOptions.has("metadata"));
  }

  /** 测试意图：runtime 所有权顶层字段被冲突值污染时明确拒绝，异常只暴露字段名、不回显原生值。 */
  @Test
  void test_runtimeOwnedFieldConflictsRejectedWithoutEchoingValues() throws Exception {
    record ConflictCase(String options, String field, String forbiddenEcho) {}
    List<ConflictCase> cases =
        List.of(
            new ConflictCase("{\"model\":\"gpt-4o\"}", "model", "gpt-4o"),
            new ConflictCase("{\"stream\":false}", "stream", "false"),
            new ConflictCase("{\"store\":true}", "store", "true"),
            new ConflictCase(
                "{\"instructions\":\"other instruction\"}", "instructions", "other instruction"),
            new ConflictCase("{\"input\":[{\"type\":\"message\"}]}", "input", "message"),
            new ConflictCase("{\"max_output_tokens\":8}", "max_output_tokens", "8"));

    for (ConflictCase conflictCase : cases) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () ->
                  encode(
                      request(
                          variant("native", "low", conflictCase.options()),
                          userMessage(),
                          List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind(), conflictCase.field());
      assertTrue(ex.getMessage().contains(conflictCase.field()), ex.getMessage());
      assertFalse(ex.getMessage().contains(conflictCase.forbiddenEcho()), ex.getMessage());
    }
  }

  /** 测试意图：与 runtime 事实同值的冗余声明不构成冲突；runtime 值仍然是 wire 上的唯一事实。 */
  @Test
  void test_runtimeOwnedFieldsWithIdenticalValuesAreAccepted() throws Exception {
    String options =
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,"
            + "\"instructions\":\"Test system instruction.\",\"max_output_tokens\":1024,\"input\":[]}";
    JsonNode root = encode(request(variant("native", null, options), List.of(), List.of()));

    assertEquals("gpt-5.4-mini", root.path("model").asText());
    assertTrue(root.path("stream").asBoolean());
    assertFalse(root.path("store").asBoolean());
    assertEquals(1024, root.path("max_output_tokens").asInt());
    assertEquals(0, root.path("input").size());
  }

  /**
   * 测试意图：prompt cache 控制字段完全由 runtime 拥有 —— 即使当前模式不会下发任何 cache hint，原生声明也必须被明确拒绝，
   * 绝不静默丢弃或让缓存键与冻结前缀哈希脱钩。
   */
  @Test
  void test_runtimeOwnedPromptCacheFieldsAreRejected() {
    for (String options :
        List.of(
            "{\"prompt_cache_key\":\"k\"}",
            "{\"prompt_cache_retention\":\"24h\"}",
            "{\"prompt_cache_options\":{\"mode\":\"explicit\"}}")) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encode(request(variant("native", null, options), userMessage(), List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertTrue(ex.getMessage().contains("prompt_cache"), ex.getMessage());
    }
  }

  /** 测试意图：与 stateless replay 不变量冲突的服务端会话状态字段存在即明确拒绝。 */
  @Test
  void test_statefulFieldsConflictWithStatelessReplay() {
    for (String options :
        List.of("{\"previous_response_id\":\"resp_1\"}", "{\"conversation\":\"conv_1\"}")) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encode(request(variant("native", null, options), userMessage(), List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertTrue(ex.getMessage().contains("stateless replay"), ex.getMessage());
    }
  }

  /** 测试意图：可执行的 hosted 工具按声明顺序保留在 runtime function 工具之前，原生字段无损透传。 */
  @Test
  void test_nativeToolsPrecedeRuntimeFunctionTools() throws Exception {
    String nativeTools =
        """
        [
          {"type": "web_search"},
          {"type": "web_search_preview"},
          {"type": "file_search", "vector_store_ids": ["vs_1"]},
          {"type": "code_interpreter", "container": {"type": "auto"}},
          {"type": "image_generation", "size": "1024x1024"},
          {"type": "mcp", "server_label": "srv", "server_url": "https://example.com/mcp", "require_approval": "never"}
        ]
        """;
    JsonNode root =
        encode(
            request(
                variant("native", null, "{\"tools\":" + nativeTools + "}"),
                userMessage(),
                List.of(functionTool("lookup"))));

    JsonNode tools = root.get("tools");
    JsonNode expectedNative = MAPPER.readTree(nativeTools);
    assertEquals(expectedNative.size() + 1, tools.size());
    for (int i = 0; i < expectedNative.size(); i++) {
      assertEquals(expectedNative.get(i), tools.get(i), "native tool " + i);
    }
    assertEquals("function", tools.get(expectedNative.size()).path("type").asText());
    assertEquals("lookup", tools.get(expectedNative.size()).path("name").asText());

    // 仅原生工具时同样完整保留（runtime 无 function 工具可追加）
    JsonNode nativeOnly =
        encode(
            request(
                variant("native", null, "{\"tools\":" + nativeTools + "}"),
                userMessage(),
                List.of()));
    assertEquals(expectedNative, nativeOnly.get("tools"));

    // 原生工具参与冻结前缀哈希：声明与否必须产生不同哈希
    assertNotEquals(
        encoder
            .encode(
                request(
                    variant("native", null, "{\"tools\":" + nativeTools + "}"),
                    userMessage(),
                    List.of()),
                createDescriptor(),
                OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash(),
        encoder
            .encode(
                request(new ModelVariant("plain"), userMessage(), List.of()),
                createDescriptor(),
                OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash());
  }

  /** 测试意图：tools 声明为非数组时本地明确失败，绝不把非法容器透传到上游。 */
  @Test
  void test_nativeToolsMustBeArray() {
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encode(
                    request(
                        variant("native", null, "{\"tools\":{\"type\":\"web_search\"}}"),
                        userMessage(),
                        List.of())));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertTrue(ex.getMessage().contains("tools"), ex.getMessage());
  }

  /** 测试意图：只接受能够由当前流式运行时闭环的 hosted 工具与同步请求，非法项必须在编码时拒绝。 */
  @Test
  void rejectsUnsupportedExecutionOptions() {
    for (String option :
        List.of(
            "{\"background\":true}",
            "{\"background\":null}",
            "{\"background\":\"false\"}",
            "{\"tools\":[null]}",
            "{\"tools\":[{\"type\":\"function\",\"name\":\"secret\"}]}",
            "{\"tools\":[{\"type\":\"custom\"}]}",
            "{\"tools\":[{\"type\":\"computer_use_preview\"}]}",
            "{\"tools\":[{\"type\":\"local_shell\"}]}",
            "{\"tools\":[{\"type\":\"shell\"}]}",
            "{\"tools\":[{\"type\":\"apply_patch\"}]}",
            "{\"tools\":[{\"type\":\"tool_search\"}]}",
            "{\"tools\":[{\"type\":\"mcp\",\"require_approval\":\"always\"}]}",
            "{\"tools\":[{\"type\":\"mcp\",\"require_approval\":false}]}")) {
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () -> encode(request(variant("v", null, option), userMessage(), List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertFalse(error.getMessage().contains("secret"));
    }
  }

  /** 测试意图：hosted 工具若提供名称，重复名称与 runtime 绑定冲突均必须在编码阶段失败。 */
  @Test
  void rejectsNamedToolCollisions() {
    for (String options :
        List.of(
            "{\"tools\":[{\"type\":\"web_search\",\"name\":\"lookup\"}]}",
            "{\"tools\":[{\"type\":\"web_search\",\"name\":\"same\"},"
                + "{\"type\":\"file_search\",\"name\":\"same\"}]}")) {
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () ->
                  encode(
                      request(
                          variant("v", null, options),
                          userMessage(),
                          List.of(functionTool("lookup")))));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertTrue(error.getMessage().contains("tools["));
    }
  }

  /**
   * 测试意图：reasoning 合并只裁决 effort 与缺省 summary —— 原生子字段保留，runtime effort 生效，未声明 summary 时延续 auto 默认，
   * 冲突 effort 明确拒绝；effort 为 null 时整个原生 reasoning 原样保留。
   */
  @Test
  void test_reasoningMergePreservesNativeSubfields() throws Exception {
    // 1. 原生已有 summary 与扩展子字段：不覆盖，只写 runtime effort 并补齐 include
    JsonNode preserved =
        encode(
            request(
                variant(
                    "native",
                    "high",
                    "{\"reasoning\":{\"summary\":\"concise\",\"effort\":\"high\",\"verbosity\":\"low\"}}"),
                userMessage(),
                List.of()));
    assertEquals("high", preserved.path("reasoning").path("effort").asText());
    assertEquals("concise", preserved.path("reasoning").path("summary").asText());
    assertEquals("low", preserved.path("reasoning").path("verbosity").asText());
    assertEquals(List.of("reasoning.encrypted_content"), textValues(preserved.get("include")));

    // 2. 原生未声明 summary：延续 runtime 的 auto 默认
    JsonNode defaulted =
        encode(
            request(
                variant("native", "minimal", "{\"reasoning\":{\"verbosity\":\"medium\"}}"),
                userMessage(),
                List.of()));
    assertEquals("minimal", defaulted.path("reasoning").path("effort").asText());
    assertEquals("auto", defaulted.path("reasoning").path("summary").asText());
    assertEquals("medium", defaulted.path("reasoning").path("verbosity").asText());

    // 3. effort 为 null：整个原生 reasoning（含 effort）原样保留，且绝不追加 include
    JsonNode untouched =
        encode(
            request(
                variant(
                    "native",
                    null,
                    "{\"reasoning\":{\"effort\":\"xhigh\",\"summary\":\"detailed\"}}"),
                userMessage(),
                List.of()));
    assertEquals(
        MAPPER.readTree("{\"effort\":\"xhigh\",\"summary\":\"detailed\"}"),
        untouched.get("reasoning"));
    assertFalse(untouched.has("include"));

    // 4. off：写协议关闭值 none，保留原生其他子字段，且不追加 summary/include
    JsonNode off =
        encode(
            request(
                variant(
                    "native",
                    "off",
                    "{\"reasoning\":{\"effort\":\"none\",\"summary\":\"concise\"}}"),
                userMessage(),
                List.of()));
    assertEquals("none", off.path("reasoning").path("effort").asText());
    assertEquals("concise", off.path("reasoning").path("summary").asText());
    assertFalse(off.has("include"));
  }

  /** 测试意图：原生 reasoning 的 effort 与 runtime effort 冲突时明确拒绝，异常不回显原生 effort 值。 */
  @Test
  void test_reasoningEffortConflictRejectedWithoutEcho() {
    for (String options :
        List.of(
            "{\"reasoning\":{\"effort\":\"xhigh\"}}",
            "{\"reasoning\":{\"effort\":\"none\"}}",
            "{\"reasoning\":{\"effort\":5}}")) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encode(request(variant("native", "low", options), userMessage(), List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
      assertTrue(ex.getMessage().contains("reasoning.effort"), ex.getMessage());
      assertFalse(ex.getMessage().contains("xhigh"), ex.getMessage());
      assertFalse(ex.getMessage().contains("none"), ex.getMessage());
    }
  }

  /** 测试意图：reasoning/include 声明为非对象/数组时本地明确失败。 */
  @Test
  void test_reasoningAndIncludeContainerTypesEnforced() {
    for (String options :
        List.of("{\"reasoning\":\"high\"}", "{\"include\":\"reasoning.encrypted_content\"}")) {
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encode(request(variant("native", "low", options), userMessage(), List.of())));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    }
  }

  /**
   * 测试意图：include 保留原生条目并按首次出现顺序去重，runtime 只在需要 encrypted content 且尚未声明时追加， 绝不覆盖原生 include；effort 为
   * null 时不触碰原生 include。
   */
  @Test
  void test_includeMergeDedupesAndEnsuresEncryptedContent() throws Exception {
    JsonNode merged =
        encode(
            request(
                variant(
                    "native",
                    "high",
                    "{\"include\":[\"web_search_call.action.sources\",\"reasoning.encrypted_content\","
                        + "\"web_search_call.action.sources\"]}"),
                userMessage(),
                List.of()));
    assertEquals(
        List.of("web_search_call.action.sources", "reasoning.encrypted_content"),
        textValues(merged.get("include")));

    JsonNode appended =
        encode(
            request(
                variant("native", "high", "{\"include\":[\"web_search_call.action.sources\"]}"),
                userMessage(),
                List.of()));
    assertEquals(
        List.of("web_search_call.action.sources", "reasoning.encrypted_content"),
        textValues(appended.get("include")));

    JsonNode untouched =
        encode(
            request(
                variant("native", null, "{\"include\":[\"web_search_call.action.sources\"]}"),
                userMessage(),
                List.of()));
    assertEquals(List.of("web_search_call.action.sources"), textValues(untouched.get("include")));
  }

  /** 测试意图：原生 tools 与 runtime function 工具并存时，二者都在 wire 上且 replay 前缀哈希覆盖合并结果。 */
  @Test
  void test_mergedToolsRemainReplayCompatible() throws Exception {
    ProviderToolDefinition tool = functionTool("lookup");
    ProviderRequest withNative =
        request(
            variant("native", null, "{\"tools\":[{\"type\":\"web_search\"}]}"),
            userMessage(),
            List.of(tool));
    ProviderRequest withoutNative =
        request(new ModelVariant("plain"), userMessage(), List.of(tool));

    JsonNode nativeRoot = encode(withNative);
    JsonNode plainRoot = encode(withoutNative);

    assertEquals(2, nativeRoot.get("tools").size());
    assertEquals("web_search", nativeRoot.get("tools").get(0).path("type").asText());
    assertEquals("function", nativeRoot.get("tools").get(1).path("type").asText());
    assertEquals(1, plainRoot.get("tools").size());

    // 同一 native 工具声明重复编码稳定（前缀哈希可冻结、可比较）
    assertEquals(
        encoder
            .encode(withNative, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash(),
        encoder
            .encode(withNative, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
            .sourcePrefixHash());
  }

  /** 测试意图：空 protocolOptions 与显式空 object 等价，行为与完全不声明原生选项一致。 */
  @Test
  void test_emptyProtocolOptionsBehaveAsUnset() throws Exception {
    JsonNode explicitEmpty =
        encode(request(variant("native", "low", "{}"), userMessage(), List.of()));
    JsonNode unset = encode(request(new ModelVariant("plain", "low"), userMessage(), List.of()));

    assertEquals(unset, explicitEmpty);
  }

  /** 测试意图：原生选项不改变 assistant replay 路径之外的行为，工具调用仍按既有形态编码。 */
  @Test
  void test_nativeOptionsDoNotAlterRuntimeMessageEncoding() throws Exception {
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi"))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(
                    new ProviderToolCallBlock(
                        new ProviderToolCall("c1", "lookup", "{\"a\":\"b\"}")))));
    JsonNode root =
        encode(request(variant("native", null, "{\"truncation\":\"auto\"}"), messages, List.of()));

    assertEquals("auto", root.path("truncation").asText());
    assertEquals(2, root.get("input").size());
    assertEquals("message", root.get("input").get(0).path("type").asText());
    assertEquals("function_call", root.get("input").get(1).path("type").asText());
    assertEquals("c1", root.get("input").get(1).path("call_id").asText());
  }

  private static List<String> textValues(JsonNode arrayNode) {
    List<String> values = new ArrayList<>();
    for (JsonNode item : arrayNode) {
      values.add(item.asText());
    }
    return values;
  }
}
