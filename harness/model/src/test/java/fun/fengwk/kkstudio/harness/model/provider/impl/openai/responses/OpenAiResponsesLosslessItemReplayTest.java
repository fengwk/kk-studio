package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 OpenAI Responses stateless replay 对官方已知 output item 的无损回放。
 *
 * <p>官方回放要求把 {@code response.output} 的每个 item 原样带回：已知 {@code message}/{@code reasoning}/{@code
 * function_call} 的 {@code id}/{@code status}、output_text 的 {@code annotations}/{@code
 * logprobs}、reasoning 的密文 与未来成员都必须在「流式累积 → replay state → 下一轮请求编码」后逐字保留；同时 durable
 * 文本/思考/工具调用仍被严格校验， 结构损坏与语义矛盾仍然失败。
 */
class OpenAiResponsesLosslessItemReplayTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final ModelVariant DEFAULT_VARIANT =
      new ModelVariant("default", "medium", PromptCacheRetention.NONE, null);
  private static final String INSTRUCTION = "Test system instruction.";

  private static final String MESSAGE_ITEM =
      "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\","
          + "\"phase\":\"final\",\"content\":[{\"type\":\"output_text\",\"text\":\"sunny\","
          + "\"annotations\":[{\"type\":\"url_citation\",\"start_index\":0,\"end_index\":5,"
          + "\"url\":\"https://example.com\",\"title\":\"src\"}],"
          + "\"logprobs\":[{\"token\":\"sun\",\"logprob\":-0.1,\"bytes\":[115]}]}]}";

  private static final String REASONING_ITEM =
      "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
          + "\"encrypted_content\":\"enc_lossless\","
          + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"check the weather\"}],"
          + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"private reasoning\"}],"
          + "\"future_member\":{\"nested\":true}}";

  private static final String FUNCTION_CALL_ITEM =
      "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_1\","
          + "\"name\":\"query\",\"arguments\":\"{\\\"q\\\":\\\"Paris\\\"}\","
          + "\"future_member\":{\"nested\":true}}";
  private static final String UNKNOWN_ITEM =
      "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\","
          + "\"action\":{\"query\":\"Paris\"}}";

  private final OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

  private static ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private static ProviderRequest request(List<ProviderMessage> messages) {
    ModelDescriptor model =
        new ModelDescriptor(
            "openai_test",
            "gpt-5.4-mini",
            "gpt-5.4-mini",
            Set.of(ModelInputModality.TEXT),
            true,
            true);
    return new ProviderRequest(
        model,
        DEFAULT_VARIANT,
        1024,
        INSTRUCTION,
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderMessage userMessage() {
    return new ProviderMessage(
        ProviderMessageRole.USER, List.of(new ProviderTextBlock("weather?")));
  }

  /** 喂入「created + 终态 output」两帧，复现真实 stateless replay 的捕获阶段。 */
  private ProviderCompletion accumulateTerminalItems(String... terminalItemJsons) throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), e -> {});
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_lossless\"}}"));
    StringBuilder output = new StringBuilder("[");
    for (int i = 0; i < terminalItemJsons.length; i++) {
      output.append(i == 0 ? "" : ",").append(terminalItemJsons[i]);
    }
    output.append("]");
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_lossless\","
                + "\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"output\":"
                + output
                + "}}"));
    return accumulator.finish();
  }

  private ArrayNode encodeNextRequest(List<ProviderContentBlock> assistantBlocks) throws Exception {
    return (ArrayNode)
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            List.of(
                                userMessage(),
                                new ProviderMessage(ProviderMessageRole.ASSISTANT, assistantBlocks),
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("thanks"))))),
                        createDescriptor())
                    .bodyUtf8Bytes())
            .get("input");
  }

  @Test
  void responseBlocksReplayOrderedNativeItemsFromOwningBlocks() throws Exception {
    ProviderCompletion completion =
        accumulateTerminalItems(MESSAGE_ITEM, REASONING_ITEM, FUNCTION_CALL_ITEM, UNKNOWN_ITEM);
    List<ProviderContentBlock> blocks = completion.response().blocks();
    ArrayNode input = encodeNextRequest(blocks);

    assertEquals(6, input.size());
    assertEquals(MAPPER.readTree(MESSAGE_ITEM), input.get(1));
    assertEquals(MAPPER.readTree(REASONING_ITEM), input.get(2));
    assertEquals(MAPPER.readTree(FUNCTION_CALL_ITEM), input.get(3));
    assertEquals(MAPPER.readTree(UNKNOWN_ITEM), input.get(4));
  }

  @Test
  void crossModelBlocksUseReadableThinkingAsTextAndDropOpaqueAndEncryptedFields() throws Exception {
    var otherAffinity = createDescriptor().affinity("gpt-4o");
    List<ProviderContentBlock> blocks =
        List.of(
            new ProviderThinkingBlock(
                "private thought",
                new ProviderBlockData(
                    ProviderReplayFormat.OPENAI_RESPONSES,
                    otherAffinity,
                    null,
                    "encrypted",
                    "rs_1",
                    "reasoning",
                    "summary_text",
                    false,
                    null,
                    true,
                    MAPPER.createObjectNode())),
            new ProviderTextBlock(
                "answer",
                new ProviderBlockData(
                    ProviderReplayFormat.OPENAI_RESPONSES,
                    otherAffinity,
                    null,
                    null,
                    "msg_1",
                    "message",
                    "output_text",
                    false,
                    null,
                    true,
                    MAPPER.createObjectNode())),
            new ProviderOpaqueBlock(
                "web_search_call",
                new ProviderBlockData(
                    ProviderReplayFormat.OPENAI_RESPONSES,
                    otherAffinity,
                    null,
                    null,
                    "ws_1",
                    "web_search_call",
                    null,
                    false,
                    null,
                    true,
                    MAPPER.createObjectNode())));
    JsonNode encoded =
        MAPPER.readTree(
            encoder
                .encode(
                    request(
                        List.of(
                            userMessage(),
                            new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks))),
                    createDescriptor())
                .bodyUtf8Bytes());
    ArrayNode input = (ArrayNode) encoded.path("input");

    assertEquals(2, input.size());
    assertEquals("message", input.get(1).path("type").asString(""));
    assertEquals(
        "private thoughtanswer", input.get(1).path("content").get(0).path("text").asString(""));
    assertFalse(input.get(1).has("encrypted_content"));
    assertFalse(input.get(1).path("content").get(0).has("thought"));
  }

  /**
   * 测试意图：终态 message 的官方字段（id/status/phase 与 output_text 的 annotations/logprobs）必须在 replay state
   * 与下一轮 请求中逐字保留，同时 durable 文本仍然是回放可用性的硬约束。
   */
  @Test
  void terminalMessageItemReplaysLosslesslyAndKeepsDurableText() throws Exception {
    ProviderCompletion completion = accumulateTerminalItems(MESSAGE_ITEM);
    assertEquals(1, completion.response().blocks().size());
    ProviderTextBlock textBlock = (ProviderTextBlock) completion.response().blocks().getFirst();
    assertEquals("msg_1", textBlock.providerBlockData().itemId());
    assertEquals("message", textBlock.providerBlockData().itemType());
    assertEquals("output_text", textBlock.providerBlockData().contentType());
    assertEquals(
        "completed",
        textBlock
            .providerBlockData()
            .nativeFields()
            .path("itemFields")
            .path("status")
            .asString(""));
    JsonNode expectedItem = MAPPER.readTree(MESSAGE_ITEM);

    ArrayNode input = encodeNextRequest(completion.response().blocks());
    assertEquals(3, input.size());
    assertEquals(expectedItem, input.get(1), "block metadata preserves the source message fields");
  }

  /** 测试意图：终态 reasoning 的 id/status/密文/摘要/未来成员整体保留，且 durable 思考仍与摘要严格一致。 */
  @Test
  void terminalReasoningItemReplaysLosslesslyAndKeepsDurableThinking() throws Exception {
    ProviderCompletion completion = accumulateTerminalItems(REASONING_ITEM);
    assertEquals(
        "check the weather",
        ((ProviderThinkingBlock) completion.response().blocks().getFirst()).thinking());
    ProviderThinkingBlock thinkingBlock =
        (ProviderThinkingBlock) completion.response().blocks().getFirst();
    assertEquals("enc_lossless", thinkingBlock.providerBlockData().encryptedContent());
    assertEquals("rs_1", thinkingBlock.providerBlockData().itemId());
    assertEquals("reasoning", thinkingBlock.providerBlockData().itemType());
    assertEquals("summary_text", thinkingBlock.providerBlockData().contentType());
    assertEquals(
        Boolean.TRUE,
        thinkingBlock
            .providerBlockData()
            .nativeFields()
            .path("itemFields")
            .path("future_member")
            .path("nested")
            .asBoolean());
    JsonNode expectedItem = MAPPER.readTree(REASONING_ITEM);

    ArrayNode input = encodeNextRequest(completion.response().blocks());
    assertEquals(expectedItem, input.get(1), "block metadata preserves the source reasoning item");
  }

  /** Same-model replay preserves every summary part and its per-part native fields. */
  @Test
  void multiPartReasoningSummaryReplaysExactlyWithoutDuplicatingText() throws Exception {
    String itemJson =
        "{\"id\":\"rs_multi\",\"type\":\"reasoning\",\"status\":\"completed\","
            + "\"encrypted_content\":\"enc_multi\",\"summary\":["
            + "{\"type\":\"summary_text\",\"text\":\"A\",\"channel\":\"analysis\"},"
            + "{\"type\":\"summary_text\",\"text\":\"B\",\"metadata\":{\"index\":2}}]}";
    ProviderCompletion completion = accumulateTerminalItems(itemJson);

    assertEquals(2, completion.response().blocks().size());
    ProviderThinkingBlock first = (ProviderThinkingBlock) completion.response().blocks().get(0);
    ProviderThinkingBlock second = (ProviderThinkingBlock) completion.response().blocks().get(1);
    assertEquals("A", first.thinking());
    assertEquals("B", second.thinking());
    assertFalse(first.providerBlockData().nativeFields().path("summaryPartFields").has("text"));
    assertFalse(second.providerBlockData().nativeFields().path("summaryPartFields").has("text"));

    ArrayNode input = encodeNextRequest(completion.response().blocks());
    JsonNode expectedItem = MAPPER.readTree(itemJson);
    assertEquals(expectedItem, input.get(1));
    assertEquals(2, input.get(1).path("summary").size());
    assertEquals("A", input.get(1).path("summary").get(0).path("text").asString(""));
    assertEquals("B", input.get(1).path("summary").get(1).path("text").asString(""));
  }

  /** Same-model replay retains an encrypted reasoning item with an empty summary. */
  @Test
  void emptyReasoningSummaryReplaysAsAnEmptySummary() throws Exception {
    String itemJson =
        "{\"id\":\"rs_empty\",\"type\":\"reasoning\",\"status\":\"completed\","
            + "\"encrypted_content\":\"enc_empty\",\"summary\":[],\"future_member\":true}";
    ProviderCompletion completion = accumulateTerminalItems(itemJson);

    assertEquals(1, completion.response().blocks().size());
    assertTrue(completion.response().blocks().getFirst() instanceof ProviderOpaqueBlock);
    ArrayNode input = encodeNextRequest(completion.response().blocks());
    assertEquals(MAPPER.readTree(itemJson), input.get(1));
    assertEquals(0, input.get(1).path("summary").size());
  }

  /** 测试意图：终态 function_call 的 id/status/未来成员整体保留，durable 工具调用语义仍被严格比对。 */
  @Test
  void terminalFunctionCallItemReplaysLosslesslyAndKeepsDurableToolCall() throws Exception {
    ProviderCompletion completion = accumulateTerminalItems(FUNCTION_CALL_ITEM);
    ProviderToolCallBlock toolCallBlock =
        (ProviderToolCallBlock) completion.response().blocks().getFirst();
    assertEquals("call_1", toolCallBlock.toolCall().id());
    assertEquals(
        Boolean.TRUE,
        toolCallBlock
            .providerBlockData()
            .nativeFields()
            .path("itemFields")
            .path("future_member")
            .path("nested")
            .asBoolean());
    assertEquals("fc_1", toolCallBlock.providerBlockData().itemId());
    JsonNode expectedItem = MAPPER.readTree(FUNCTION_CALL_ITEM);

    ArrayNode input = encodeNextRequest(completion.response().blocks());
    assertEquals(
        expectedItem, input.get(1), "block metadata preserves the source function_call item");
  }

  /** 测试意图：终态 reasoning 缺失的密文由流式事实补齐，保证可回放的原生推理不被丢失。 */
  @Test
  void streamedEncryptedReasoningIsStillRetainedInLosslessReplay() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), e -> {});
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_enc\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                + "\"encrypted_content\":\"enc_stream\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"check the weather\"}]}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_enc\",\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},"
                + "\"output\":[{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"check the weather\"}]}]}}"));

    ProviderThinkingBlock thinking =
        (ProviderThinkingBlock) accumulator.response().blocks().getFirst();
    assertEquals("enc_stream", thinking.providerBlockData().encryptedContent());
    assertEquals(
        "completed",
        thinking.providerBlockData().nativeFields().path("itemFields").path("status").asString(""));
  }

  /**
   * 测试意图：权威 item 未携带 arguments 时仍由流式参数增量补齐（最小修复），而它自身的 id/status 等官方字段继续无损保留， durable 工具调用语义保持一致。
   */
  @Test
  void functionCallArgumentsRepairedFromStreamKeepOfficialFields() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), e -> {});
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_repair\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"in_progress\","
                + "\"call_id\":\"call_1\",\"name\":\"query\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"arguments\":\"{\\\"q\\\":\\\"Paris\\\"}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\","
                + "\"call_id\":\"call_1\",\"name\":\"query\"}}"));
    // Terminal omission keeps the streamed tool arguments on its owning block.
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_repair\","
                + "\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));

    JsonNode expectedItem =
        MAPPER.readTree(
            "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\","
                + "\"call_id\":\"call_1\",\"name\":\"query\","
                + "\"arguments\":\"{\\\"q\\\":\\\"Paris\\\"}\"}");
    ProviderToolCallBlock toolCall =
        (ProviderToolCallBlock) accumulator.response().blocks().getFirst();
    assertEquals("{\"q\":\"Paris\"}", toolCall.toolCall().argumentsJson());
    assertEquals("fc_1", toolCall.providerBlockData().itemId());
    assertEquals(
        "completed",
        toolCall.providerBlockData().nativeFields().path("itemFields").path("status").asString(""));

    ArrayNode input = encodeNextRequest(accumulator.response().blocks());
    assertEquals(expectedItem, input.get(1));
  }

  /** Malformed free-form native fields are rejected when constructing block metadata. */
  @Test
  void blockMetadataRequiresObjectNativeFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderBlockData(
                ProviderReplayFormat.OPENAI_RESPONSES,
                createDescriptor().affinity("gpt-5.4-mini"),
                MAPPER.createArrayNode()));
  }
}
