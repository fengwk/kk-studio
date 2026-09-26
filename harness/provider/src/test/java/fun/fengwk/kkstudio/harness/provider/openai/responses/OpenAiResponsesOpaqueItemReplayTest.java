package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 stateless replay 对未知官方 output item 的不透明透传。
 *
 * <p>未知 item（web/file search、image generation、code interpreter、custom tool call 等）在 affinity
 * 与冻结前缀哈希 通过后原样上 wire；已知 {@code message}/{@code reasoning}/{@code function_call} 的字段校验与 durable
 * 语义一致性 仍然严格，额外 unknown 字段不能把已知类型洗成不透明 item。
 */
class OpenAiResponsesOpaqueItemReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");
  private static final String INSTRUCTION = "Test system instruction.";

  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  private static ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private static ModelDescriptor createModel() {
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
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
            BigDecimal.ONE));
  }

  /** 前缀哈希在 assistant 消息之前计算：本测试只用一条 assistant 消息且无工具，因此前缀哈希基于空 tools/input。 */
  private static String prefixHashBeforeAssistantMessage() {
    return OpenAiResponsesPrefixHasher.calculateHash(
        INSTRUCTION, MAPPER.createArrayNode(), MAPPER.createArrayNode());
  }

  private static ObjectNode payloadWith(ArrayNode output) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.set("output", output);
    return payload;
  }

  private static ProviderReplayState replayState(ObjectNode payload, String sourcePrefixHash) {
    return new ProviderReplayState(
        ProviderReplayFormat.OPENAI_RESPONSES,
        createDescriptor().affinity("gpt-5.4-mini"),
        sourcePrefixHash,
        payload);
  }

  private ObjectNode encodeWithReplay(
      ProviderReplayState replayState, List<ProviderContentBlock> durableContents)
      throws Exception {
    ProviderRequest request =
        new ProviderRequest(
            createModel(),
            DEFAULT_VARIANT,
            1024,
            INSTRUCTION,
            List.of(
                new ProviderMessage(ProviderMessageRole.ASSISTANT, durableContents, replayState)),
            List.of(),
            ProviderCacheControl.none());
    return (ObjectNode)
        MAPPER.readTree(
            encoder
                .encode(request, createDescriptor(), OpenAiResponsesConfig.defaultConfig())
                .bodyUtf8Bytes());
  }

  /** 测试意图：未知官方 output item 在哈希/代际校验通过后原样透传，位置与内容与冻结事实完全一致（不透明 replay）。 */
  @Test
  void test_unknownProviderItemsReplayInPlaceVerbatim() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(
        MAPPER.readTree(
            "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\","
                + "\"action\":{\"type\":\"search\",\"query\":\"weather\"}}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"image_generation_call\",\"id\":\"img_1\",\"status\":\"completed\",\"result\":\"aGVsbG8=\"}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"custom_tool_call\",\"call_id\":\"ct_1\",\"name\":\"my_tool\",\"input\":\"{\\\"k\\\":1}\"}"));
    output.add(
        MAPPER.readTree("{\"type\":\"file_search_call\",\"id\":\"fs_1\",\"queries\":[\"a\"]}"));

    ObjectNode root =
        encodeWithReplay(
            replayState(payloadWith(output), prefixHashBeforeAssistantMessage()),
            List.of(new ProviderTextBlock("sunny")));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals(5, input.size());
    for (int i = 0; i < output.size(); i++) {
      assertEquals(output.get(i), input.get(i), "replayed item " + i);
    }
    // 原生 opaque 事实只出现在 input 的 replay 位置，不泄漏到其他顶层字段
    assertFalse(root.has("encrypted_content"));
  }

  /** 测试意图：未知 item 不参与 durable 语义比对，但已知 message 的 durable 文本一致性仍然严格。 */
  @Test
  void test_durableConsistencyStillEnforcedWithUnknownItemsPresent() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\"}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"replayed text\"}]}"));
    ProviderReplayState replayState =
        replayState(payloadWith(output), prefixHashBeforeAssistantMessage());

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> encodeWithReplay(replayState, List.of(new ProviderTextBlock("durable text"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertFalse(ex.getMessage().contains("replayed text"), ex.getMessage());
  }

  /**
   * 测试意图：已知类型的字段校验不能被额外 unknown 字段绕过 —— 带额外字段的 message/reasoning/function_call 仍然被拒绝，
   * 绝不会因为多出一个字段就被当作不透明 item 透传。
   */
  @Test
  void test_knownTypeCannotBypassValidationThroughExtraFields() throws Exception {
    List<ObjectNode> invalidKnownItems =
        List.of(
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"message\",\"role\":\"assistant\",\"unknown_prop\":true,"
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"reasoning\",\"encrypted_content\":\"enc\",\"unknown_prop\":1}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"calc\",\"arguments\":\"{}\","
                        + "\"unknown_prop\":\"x\"}"));

    for (ObjectNode item : invalidKnownItems) {
      ArrayNode output = MAPPER.createArrayNode();
      output.add(item);
      ProviderReplayState replayState =
          replayState(payloadWith(output), prefixHashBeforeAssistantMessage());
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encodeWithReplay(replayState, List.of(new ProviderTextBlock("hi"))));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind(), item.path("type").asText());
    }
  }

  /** 测试意图：未知 item 的结构事实不合法（非 object、缺 type、type 非非空白字符串）时明确拒绝。 */
  @Test
  void test_malformedUnknownItemsStillRejected() throws Exception {
    for (String malformedItemJson :
        List.of(
            "12345",
            "{\"id\":\"no_type\"}",
            "{\"type\":\"  \"}",
            "{\"type\":5}",
            "{\"type\":null}")) {
      ArrayNode output = MAPPER.createArrayNode();
      output.add(MAPPER.readTree(malformedItemJson));
      ProviderReplayState replayState =
          replayState(payloadWith(output), prefixHashBeforeAssistantMessage());
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encodeWithReplay(replayState, List.of(new ProviderTextBlock("hi"))));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind(), malformedItemJson);
    }
  }

  /** 测试意图：哈希或代际失配时不透传未知 item，按既有不变量回退语义编码，而不是继续发送陈旧的原生事实。 */
  @Test
  void test_hashMismatchFallsBackToSemanticEncoding() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\"}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    ProviderReplayState staleReplayState =
        replayState(
            payloadWith(output),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

    ObjectNode root = encodeWithReplay(staleReplayState, List.of(new ProviderTextBlock("hi")));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals(1, input.size());
    assertEquals("assistant", input.get(0).path("role").asText());
    assertFalse(input.toString().contains("web_search_call"));
  }

  /** 测试意图：未知 item 与已知 reasoning/tool 混合的 replay 同时成立：已知部分严格校验，未知部分原样保留。 */
  @Test
  void test_knownAndUnknownItemsCoexistInReplay() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(
        MAPPER.readTree(
            "{\"type\":\"reasoning\",\"encrypted_content\":\"enc_blob\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"durable thought\"}]}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"code_interpreter_call\",\"id\":\"ci_1\",\"status\":\"completed\",\"code\":\"1+1\"}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"calc\",\"arguments\":\"{}\"}"));

    ObjectNode root =
        encodeWithReplay(
            replayState(payloadWith(output), prefixHashBeforeAssistantMessage()),
            List.of(
                new ProviderThinkingBlock("durable thought"),
                new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals(3, input.size());
    assertEquals("reasoning", input.get(0).path("type").asText());
    assertEquals("enc_blob", input.get(0).path("encrypted_content").asText());
    assertEquals(output.get(1), input.get(1));
    assertEquals("function_call", input.get(2).path("type").asText());
    assertEquals("c1", input.get(2).path("call_id").asText());
  }

  /** 测试意图：未知 item 在 replay 中的相对顺序与冻结事实一致。 */
  @Test
  void test_unknownItemOrderIsPreserved() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"first\"}"));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    output.add(MAPPER.readTree("{\"type\":\"image_generation_call\",\"id\":\"last\"}"));

    ObjectNode root =
        encodeWithReplay(
            replayState(payloadWith(output), prefixHashBeforeAssistantMessage()),
            List.of(new ProviderTextBlock("hi")));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals("web_search_call", input.get(0).path("type").asText());
    assertEquals("message", input.get(1).path("type").asText());
    assertEquals("image_generation_call", input.get(2).path("type").asText());
    assertTrue(input.get(0).path("id").asText().equals("first"));
    assertTrue(input.get(2).path("id").asText().equals("last"));
  }
}
