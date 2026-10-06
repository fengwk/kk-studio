package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 stateless replay 对未知官方 output item 的不透明透传与亲和性回退语义。
 *
 * <p>未知 item（web/file search、image generation、code interpreter、custom tool call 等）在 affinity 匹配时原样上
 * wire；affinity 失配时回退语义编码并随之为省略，绝不因 payload 含 opaque item 而拒绝整个请求。已知 {@code message}/{@code
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
        "openai_test", "gpt-5.4-mini", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ObjectNode payloadWith(ArrayNode output) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.set("output", output);
    return payload;
  }

  /** 使用匹配 affinity 的 replay state：native/opaque item 原样原位回放。 */
  private static ProviderReplayState replayState(ObjectNode payload) {
    return replayState(payload, "gpt-5.4-mini");
  }

  /** 使用指定 wire model 的 affinity：与当前请求 model 不同即触发语义回退。 */
  private static ProviderReplayState replayState(ObjectNode payload, String modelId) {
    return new ProviderReplayState(
        ProviderReplayFormat.OPENAI_RESPONSES, createDescriptor().affinity(modelId), payload);
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
        MAPPER.readTree(encoder.encode(request, createDescriptor()).bodyUtf8Bytes());
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
        encodeWithReplay(replayState(payloadWith(output)), List.of(new ProviderTextBlock("sunny")));

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
    ProviderReplayState replayState = replayState(payloadWith(output));

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
            replayState(payloadWith(output)),
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
      ProviderReplayState replayState = replayState(payloadWith(output));
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
      ProviderReplayState replayState = replayState(payloadWith(output));
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> encodeWithReplay(replayState, List.of(new ProviderTextBlock("hi"))));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind(), malformedItemJson);
    }
  }

  /**
   * 测试意图：affinity 失配时按 durable 语义回退，绝不因 payload 含 native/opaque item 就拒绝整个请求——回退可以省略 native 事实， 但
   * durable 文本、思考与工具调用必须由语义编码完整重建。
   */
  @Test
  void test_affinityMismatchFallsBackToSemanticForAllPayloadShapes() throws Exception {
    // 1. 包含未知 hosted item（web_search_call）的 payload：affinity 失配回退语义编码，opaque item 随之省略
    ArrayNode nativeOutput = MAPPER.createArrayNode();
    nativeOutput.add(MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\"}"));
    nativeOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    JsonNode nativeInput =
        encodeWithReplay(
                replayState(payloadWith(nativeOutput), "other-model"),
                List.of(new ProviderTextBlock("hi")))
            .get("input");
    assertEquals(1, nativeInput.size());
    assertEquals("assistant", nativeInput.get(0).path("role").asText());
    assertEquals("hi", nativeInput.get(0).path("content").get(0).path("text").asText());
    assertFalse(nativeInput.toString().contains("web_search_call"));

    // 2. 只含最小 message item 的 payload：affinity 失配同样回退语义编码
    ArrayNode reconstructibleOutput = MAPPER.createArrayNode();
    reconstructibleOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}"));
    ArrayNode input =
        (ArrayNode)
            encodeWithReplay(
                    replayState(payloadWith(reconstructibleOutput), "other-model"),
                    List.of(new ProviderTextBlock("hi")))
                .get("input");
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
            replayState(payloadWith(output)),
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
        encodeWithReplay(replayState(payloadWith(output)), List.of(new ProviderTextBlock("hi")));

    ArrayNode input = (ArrayNode) root.get("input");
    assertEquals("web_search_call", input.get(0).path("type").asText());
    assertEquals("message", input.get(1).path("type").asText());
    assertEquals("image_generation_call", input.get(2).path("type").asText());
    assertTrue(input.get(0).path("id").asText().equals("first"));
    assertTrue(input.get(2).path("id").asText().equals("last"));
  }

  /**
   * 测试意图：已知 item 携带的官方附加事实（message 的 id/status/phase、output_text 的 annotations/logprobs、reasoning
   * 密文、function_call 的 status/未来成员）无法由 durable 语义等价重建：affinity 失配时一律回退语义编码并省略这些原生事实，
   * 绝不因它们存在而拒绝整个请求；durable 文本/思考/工具调用由语义编码完整重建。
   */
  @Test
  void test_affinityMismatchFallsBackForKnownItemsWithExtraFields() throws Exception {
    // 1. message 携带 id/status/phase 与 output_text 的 annotations/logprobs：affinity 失配回退语义编码
    ArrayNode extendedMessageOutput = MAPPER.createArrayNode();
    extendedMessageOutput.add(
        MAPPER.readTree(
            """
            {"type":"message","id":"msg_1","status":"completed","role":"assistant","phase":"final",
             "content":[{"type":"output_text","text":"sunny",
               "annotations":[{"type":"url_citation","url":"https://example.com"}],
               "logprobs":[{"token":"sun","logprob":-0.1}]}]}
            """));
    JsonNode messageInput =
        encodeWithReplay(
                replayState(payloadWith(extendedMessageOutput), "other-model"),
                List.of(new ProviderTextBlock("sunny")))
            .get("input");
    assertEquals(1, messageInput.size());
    assertEquals("message", messageInput.get(0).path("type").asText());
    assertEquals("sunny", messageInput.get(0).path("content").get(0).path("text").asText());
    assertFalse(messageInput.toString().contains("annotations"));

    // 2. reasoning 携带密文：affinity 失配回退语义编码，思考由 durable 承载，密文不重建
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
    JsonNode reasoningInput =
        encodeWithReplay(
                replayState(payloadWith(encryptedReasoningOutput), "other-model"),
                List.of(
                    new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("sunny")))
            .get("input");
    assertEquals(2, reasoningInput.size());
    assertEquals("reasoning", reasoningInput.get(0).path("type").asText());
    assertEquals(
        "durable thought", reasoningInput.get(0).path("summary").get(0).path("text").asText());
    assertFalse(reasoningInput.get(0).has("encrypted_content"));
    assertEquals("sunny", reasoningInput.get(1).path("content").get(0).path("text").asText());

    // 3. function_call 携带 status 与未来成员：affinity 失配回退语义编码
    ArrayNode extendedCallOutput = MAPPER.createArrayNode();
    extendedCallOutput.add(
        MAPPER.readTree(
            "{\"type\":\"function_call\",\"id\":\"fc_1\",\"status\":\"completed\",\"call_id\":\"c1\","
                + "\"name\":\"calc\",\"arguments\":\"{}\",\"future_member\":{\"nested\":true}}"));
    JsonNode callInput =
        encodeWithReplay(
                replayState(payloadWith(extendedCallOutput), "other-model"),
                List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{}"))))
            .get("input");
    assertEquals(1, callInput.size());
    assertEquals("function_call", callInput.get(0).path("type").asText());
    assertEquals("c1", callInput.get(0).path("call_id").asText());
    assertFalse(callInput.get(0).has("future_member"));

    // 4. reasoning 只有 id/status/summary：affinity 失配回退语义编码，思考不丢失
    ArrayNode summaryReasoningOutput = MAPPER.createArrayNode();
    summaryReasoningOutput.add(
        MAPPER.readTree(
            "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"durable thought\"}]}"));
    summaryReasoningOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    JsonNode summaryInput =
        encodeWithReplay(
                replayState(payloadWith(summaryReasoningOutput), "other-model"),
                List.of(
                    new ProviderThinkingBlock("durable thought"), new ProviderTextBlock("sunny")))
            .get("input");
    assertEquals(2, summaryInput.size());
    assertEquals("reasoning", summaryInput.get(0).path("type").asText());
    assertEquals(
        "durable thought", summaryInput.get(0).path("summary").get(0).path("text").asText());
    assertEquals("sunny", summaryInput.get(1).path("content").get(0).path("text").asText());

    // 5. 空 reasoning 占位符 + hosted item 混排：占位符不承载原生推理，decline 原位回放而回退语义编码；回退省略 hosted
    //    native 事实，但绝不拒绝整个请求。
    ArrayNode placeholderWithHostedOutput = MAPPER.createArrayNode();
    placeholderWithHostedOutput.add(
        MAPPER.readTree("{\"type\":\"reasoning\",\"id\":\"rs_2\",\"summary\":[]}"));
    placeholderWithHostedOutput.add(
        MAPPER.readTree(
            "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\"}]}"));
    placeholderWithHostedOutput.add(
        MAPPER.readTree("{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"}"));
    JsonNode placeholderInput =
        encodeWithReplay(
                replayState(payloadWith(placeholderWithHostedOutput)),
                List.of(new ProviderTextBlock("sunny")))
            .get("input");
    assertEquals(1, placeholderInput.size());
    assertEquals("message", placeholderInput.get(0).path("type").asText());
    assertEquals("sunny", placeholderInput.get(0).path("content").get(0).path("text").asText());
    assertFalse(placeholderInput.toString().contains("web_search_call"));
  }
}
