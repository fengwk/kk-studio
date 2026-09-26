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
 * 验证 stateless replay 对未知官方 output item 的不透明透传与 fail-closed 语义。
 *
 * <p>未知 item（web/file search、image generation、code interpreter、custom tool call 等）在 affinity
 * 与冻结前缀哈希 通过后原样上 wire，失配时则必须 fail closed（它们无法由 durable 语义重建）；已知 {@code message}/{@code
 * reasoning}/{@code function_call} 在保留全部官方响应字段的同时仍受 durable 语义所必需的结构/类型约束与一致性校验， 绝不会被额外字段洗成不透明
 * item。
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
   * 测试意图：已知类型携带官方额外字段（message 的 id/status/phase 与 output_text 的 annotations/logprobs、reasoning 与
   * function_call 的 id/status 及未来成员）时仍然是已知类型：额外字段既不触发拒绝，也不会被清洗或降级，而是与冻结事实 逐字一致地回放；同时 durable
   * 文本/思考/工具调用仍被严格校验。
   */
  @Test
  void test_knownItemsWithOfficialExtraFieldsReplayVerbatim() throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    output.add(
        MAPPER.readTree(
            """
            {"type":"message","id":"msg_1","status":"completed","role":"assistant","phase":"final",
             "content":[{"type":"output_text","text":"sunny",
               "annotations":[{"type":"url_citation","start_index":0,"end_index":5,
                 "url":"https://example.com","title":"src"}],
               "logprobs":[{"token":"sun","logprob":-0.1,"bytes":[115]}]}]}
            """));
    output.add(
        MAPPER.readTree(
            """
            {"type":"reasoning","id":"rs_1","status":"completed","encrypted_content":"enc_1",
             "summary":[{"type":"summary_text","text":"durable thought"}],
             "content":[{"type":"reasoning_text","text":"private reasoning"}]}
            """));
    output.add(
        MAPPER.readTree(
            "{\"type\":\"function_call\",\"id\":\"fc_1\",\"status\":\"completed\",\"call_id\":\"c1\","
                + "\"name\":\"calc\",\"arguments\":\"{}\",\"future_member\":{\"nested\":true}}"));

    ObjectNode root =
        encodeWithReplay(
            replayState(payloadWith(output), prefixHashBeforeAssistantMessage()),
            List.of(
                new ProviderTextBlock("sunny"),
                new ProviderThinkingBlock("durable thought"),
                new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals(3, input.size());
    for (int i = 0; i < output.size(); i++) {
      assertEquals(output.get(i), input.get(i), "replayed known item " + i);
    }
  }

  /**
   * 测试意图：放宽的只是已知类型的字段白名单；durable 语义所必需的结构/类型仍然严格 —— 非 assistant role、缺失 text 的 output_text、形态错误的
   * summary 块、缺失身份或非法 arguments 的 function_call 都必须以 INVALID_REQUEST 拒绝，绝不会因为 “额外字段可透传” 而被当作不透明
   * item 放行。
   */
  @Test
  void test_knownItemStructureStillStrictlyValidated() throws Exception {
    List<ObjectNode> invalidKnownItems =
        List.of(
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"message\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\"}]}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"wrong_type\",\"text\":\"th\"}]}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"function_call\",\"call_id\":\"c1\",\"arguments\":\"{}\"}"),
            (ObjectNode)
                MAPPER.readTree(
                    "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"calc\","
                        + "\"arguments\":\"[1]\"}"));

    for (ObjectNode item : invalidKnownItems) {
      ArrayNode output = MAPPER.createArrayNode();
      output.add(item);
      ProviderReplayState replayState =
          replayState(payloadWith(output), prefixHashBeforeAssistantMessage());
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () ->
                  encodeWithReplay(
                      replayState,
                      List.of(
                          new ProviderTextBlock("hi"),
                          new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}")))));
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

  /**
   * 测试意图：哈希或代际失配时按 payload 承载的事实分流——只含可等价重建的 message item 时回退语义编码；一旦包含未知/hosted item 等 native-only
   * 事实，就必须 fail closed，绝不静默丢弃陈旧的原生事实。
   */
  @Test
  void test_hashMismatchFailsClosedForNativeOnlyItemsAndStillFallsBackForReconstructiblePayload()
      throws Exception {
    String staleHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    // 1. 包含未知 hosted item（web_search_call）的 payload：hash 失配必须 fail closed
    ArrayNode nativeOutput = MAPPER.createArrayNode();
    nativeOutput.add(MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\"}"));
    nativeOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                encodeWithReplay(
                    replayState(payloadWith(nativeOutput), staleHash),
                    List.of(new ProviderTextBlock("hi"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(
        "native replay output items require matching affinity and source prefix hash",
        error.getMessage());

    // 2. 只含可等价重建 message item 的 payload：hash 失配仍回退语义编码
    ArrayNode reconstructibleOutput = MAPPER.createArrayNode();
    reconstructibleOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    ObjectNode root =
        encodeWithReplay(
            replayState(payloadWith(reconstructibleOutput), staleHash),
            List.of(new ProviderTextBlock("hi")));

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

  /**
   * 测试意图：已知 {@code message}/{@code reasoning}/{@code function_call} 携带的官方附加事实（message 的
   * id/status/phase、output_text 的 annotations/logprobs、reasoning 密文、function_call 的
   * status/未来成员）都无法由 durable 语义等价重建，affinity 或 prefix hash 失配时必须 fail closed；而恰好最小形态的
   * message/function_call 与只含 id/status/summary 的 reasoning 摘要仍回退语义编码。
   */
  @Test
  void test_knownItemsWithNativeOnlyFactsFailClosedOnMismatch() throws Exception {
    String staleHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    // 1. message 携带 id/status/phase 与 output_text 的 annotations/logprobs：affinity 失配 fail closed
    ArrayNode extendedMessageOutput = MAPPER.createArrayNode();
    extendedMessageOutput.add(
        MAPPER.readTree(
            """
            {"type":"message","id":"msg_1","status":"completed","role":"assistant","phase":"final",
             "content":[{"type":"output_text","text":"sunny",
               "annotations":[{"type":"url_citation","url":"https://example.com"}],
               "logprobs":[{"token":"sun","logprob":-0.1}]}]}
            """));
    assertNativeOnlyRejected(
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            createDescriptor().affinity("other-model"),
            prefixHashBeforeAssistantMessage(),
            payloadWith(extendedMessageOutput)),
        List.of(new ProviderTextBlock("sunny")));

    // 2. 同一 payload 的 prefix hash 失配同样 fail closed
    assertNativeOnlyRejected(
        replayState(payloadWith(extendedMessageOutput), staleHash),
        List.of(new ProviderTextBlock("sunny")));

    // 3. reasoning 携带密文（原生推理链）：hash 失配 fail closed，即使摘要文本与 durable thinking 一致
    ArrayNode encryptedReasoningOutput = MAPPER.createArrayNode();
    encryptedReasoningOutput.add(
        MAPPER.readTree(
            """
            {"type":"reasoning","id":"rs_1","status":"completed","encrypted_content":"enc_blob",
             "summary":[{"type":"summary_text","text":"durable thought"}]}
            """));
    encryptedReasoningOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    assertNativeOnlyRejected(
        replayState(payloadWith(encryptedReasoningOutput), staleHash),
        List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("sunny")));

    // 4. function_call 携带 status 与未来成员：affinity 失配 fail closed
    ArrayNode extendedCallOutput = MAPPER.createArrayNode();
    extendedCallOutput.add(
        MAPPER.readTree(
            "{\"type\":\"function_call\",\"id\":\"fc_1\",\"status\":\"completed\",\"call_id\":\"c1\","
                + "\"name\":\"calc\",\"arguments\":\"{}\",\"future_member\":{\"nested\":true}}"));
    assertNativeOnlyRejected(
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            createDescriptor().affinity("other-model"),
            prefixHashBeforeAssistantMessage(),
            payloadWith(extendedCallOutput)),
        List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))));

    // 5. 恰好最小形态的 message + function_call，以及无密文/无额外成员的 reasoning 摘要：affinity 与 prefix 失配都回退语义编码
    ArrayNode minimalOutput = MAPPER.createArrayNode();
    minimalOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    minimalOutput.add(
        MAPPER.readTree(
            "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"calc\",\"arguments\":\"{}\"}"));
    List<ProviderContentBlock> durableBlocks =
        List.of(
            new ProviderTextBlock("sunny"),
            new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}")));

    for (ProviderReplayState replayState :
        List.of(
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                createDescriptor().affinity("other-model"),
                prefixHashBeforeAssistantMessage(),
                payloadWith(minimalOutput)),
            replayState(payloadWith(minimalOutput), staleHash))) {
      ArrayNode input = (ArrayNode) encodeWithReplay(replayState, durableBlocks).get("input");
      assertEquals(2, input.size());
      assertEquals("message", input.get(0).path("type").asText());
      assertEquals("sunny", input.get(0).path("content").get(0).path("text").asText());
      assertEquals("function_call", input.get(1).path("type").asText());
      assertEquals("c1", input.get(1).path("call_id").asText());
    }

    // 6. reasoning 只有 id/status/summary 且摘要文本与 durable thinking 一致（无密文、无额外成员）：失配回退语义编码，思考不丢失
    ArrayNode summaryReasoningOutput = MAPPER.createArrayNode();
    summaryReasoningOutput.add(
        MAPPER.readTree(
            "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"durable thought\"}]}"));
    summaryReasoningOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    List<ProviderContentBlock> thinkingDurableBlocks =
        List.of(new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("sunny"));

    for (ProviderReplayState replayState :
        List.of(
            new ProviderReplayState(
                ProviderReplayFormat.OPENAI_RESPONSES,
                createDescriptor().affinity("other-model"),
                prefixHashBeforeAssistantMessage(),
                payloadWith(summaryReasoningOutput)),
            replayState(payloadWith(summaryReasoningOutput), staleHash))) {
      ArrayNode input =
          (ArrayNode) encodeWithReplay(replayState, thinkingDurableBlocks).get("input");
      assertEquals(2, input.size());
      assertEquals("reasoning", input.get(0).path("type").asText());
      assertEquals("durable thought", input.get(0).path("summary").get(0).path("text").asText());
      assertEquals("sunny", input.get(1).path("content").get(0).path("text").asText());
    }

    // 7. 空 reasoning 占位符 + hosted item 混排：占位符必须回退语义编码承载 durable 思考，hosted item 又只能原位回放，
    // 两者不可兼得 -> fail closed（不允许静默丢弃 hosted item）
    ArrayNode placeholderWithHostedOutput = MAPPER.createArrayNode();
    placeholderWithHostedOutput.add(
        MAPPER.readTree("{\"type\":\"reasoning\",\"id\":\"rs_2\",\"summary\":[]}"));
    placeholderWithHostedOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    placeholderWithHostedOutput.add(
        MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"}"));
    ProviderException conflict =
        assertThrows(
            ProviderException.class,
            () ->
                encodeWithReplay(
                    replayState(
                        payloadWith(placeholderWithHostedOutput),
                        prefixHashBeforeAssistantMessage()),
                    List.of(new ProviderTextBlock("sunny"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, conflict.kind());
    assertEquals(
        "native replay output items cannot be replaced by semantic fallback",
        conflict.getMessage());
  }

  /** 断言该 replay 因携带 native-only 事实而在 affinity/hash 失配时 fail closed。 */
  private void assertNativeOnlyRejected(
      ProviderReplayState replayState, List<ProviderContentBlock> durableContents) {
    ProviderException ex =
        assertThrows(ProviderException.class, () -> encodeWithReplay(replayState, durableContents));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertEquals(
        "native replay output items require matching affinity and source prefix hash",
        ex.getMessage());
  }
}
