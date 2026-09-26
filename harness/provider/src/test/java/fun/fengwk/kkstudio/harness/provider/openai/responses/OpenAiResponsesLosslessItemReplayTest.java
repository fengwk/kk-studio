package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 验证 OpenAI Responses stateless replay 对官方已知 output item 的无损回放。
 *
 * <p>官方回放要求把 {@code response.output} 的每个 item 原样带回：已知 {@code message}/{@code reasoning}/{@code
 * function_call} 的 {@code id}/{@code status}、output_text 的 {@code annotations}/{@code
 * logprobs}、reasoning 的密文 与未来成员都必须在「流式累积 → replay state → 下一轮请求编码」后逐字保留；同时 durable
 * 文本/思考/工具调用仍被严格校验， 结构损坏与语义矛盾仍然失败。
 */
class OpenAiResponsesLosslessItemReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default", "medium");
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
          + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"private reasoning\"}]}";

  private static final String FUNCTION_CALL_ITEM =
      "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_1\","
          + "\"name\":\"query\",\"arguments\":\"{\\\"q\\\":\\\"Paris\\\"}\","
          + "\"future_member\":{\"nested\":true}}";

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
    ModelDescriptor model =
        new ModelDescriptor(
            "openai_test",
            "gpt-5.4-mini",
            "gpt-5.4-mini",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
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

  /** 用第一条请求冻结的前缀哈希喂入「created + 终态 output」两帧，复现真实 stateless replay 的捕获阶段。 */
  private ProviderReplayState accumulateTerminalItems(
      String prefixHash, String... terminalItemJsons) throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), prefixHash, e -> {});
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
                + "\"status\":\"completed\",\"output\":"
                + output
                + "}}"));
    return accumulator.replayState();
  }

  /** 复用第一条请求冻结的前缀哈希编码下一轮请求；replay 位置固定在 input[1]（user → assistant replay → user）。 */
  private ArrayNode encodeNextRequest(
      ProviderReplayState replayState, List<ProviderContentBlock> durableContents)
      throws Exception {
    return (ArrayNode)
        MAPPER
            .readTree(
                encoder
                    .encode(
                        request(
                            List.of(
                                userMessage(),
                                new ProviderMessage(
                                    ProviderMessageRole.ASSISTANT, durableContents, replayState),
                                new ProviderMessage(
                                    ProviderMessageRole.USER,
                                    List.of(new ProviderTextBlock("thanks"))))),
                        createDescriptor(),
                        OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");
  }

  private String freezePrefixHash() {
    return encoder
        .encode(
            request(List.of(userMessage())),
            createDescriptor(),
            OpenAiResponsesConfig.defaultConfig())
        .sourcePrefixHash();
  }

  /**
   * 测试意图：终态 message 的官方字段（id/status/phase 与 output_text 的 annotations/logprobs）必须在 replay state
   * 与下一轮 请求中逐字保留，同时 durable 文本仍然是回放可用性的硬约束。
   */
  @Test
  void terminalMessageItemReplaysLosslesslyAndKeepsDurableText() throws Exception {
    ProviderReplayState replayState = accumulateTerminalItems(freezePrefixHash(), MESSAGE_ITEM);
    assertNotNull(replayState);
    JsonNode expectedItem = MAPPER.readTree(MESSAGE_ITEM);
    assertEquals(expectedItem, replayState.payload().path("output").get(0));

    ArrayNode input = encodeNextRequest(replayState, List.of(new ProviderTextBlock("sunny")));
    assertEquals(3, input.size());
    assertEquals(
        expectedItem, input.get(1), "replayed message item must keep every official field");

    // durable 文本仍严格：与冻结 output_text 不一致时拒绝，绝不因为字段无损就放行
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> encodeNextRequest(replayState, List.of(new ProviderTextBlock("rainy"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 测试意图：终态 reasoning 的 id/status/密文/摘要/未来成员整体保留，且 durable 思考仍与摘要严格一致。 */
  @Test
  void terminalReasoningItemReplaysLosslesslyAndKeepsDurableThinking() throws Exception {
    ProviderReplayState replayState = accumulateTerminalItems(freezePrefixHash(), REASONING_ITEM);
    assertNotNull(replayState);
    JsonNode expectedItem = MAPPER.readTree(REASONING_ITEM);
    assertEquals(expectedItem, replayState.payload().path("output").get(0));

    ArrayNode input =
        encodeNextRequest(replayState, List.of(new ProviderThinkingBlock("check the weather")));
    assertEquals(
        expectedItem, input.get(1), "replayed reasoning item must keep every official field");

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encodeNextRequest(
                    replayState, List.of(new ProviderThinkingBlock("other thought"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 测试意图：终态 function_call 的 id/status/未来成员整体保留，durable 工具调用语义仍被严格比对。 */
  @Test
  void terminalFunctionCallItemReplaysLosslesslyAndKeepsDurableToolCall() throws Exception {
    ProviderReplayState replayState =
        accumulateTerminalItems(freezePrefixHash(), FUNCTION_CALL_ITEM);
    assertNotNull(replayState);
    JsonNode expectedItem = MAPPER.readTree(FUNCTION_CALL_ITEM);
    assertEquals(expectedItem, replayState.payload().path("output").get(0));

    ArrayNode input =
        encodeNextRequest(
            replayState,
            List.of(
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "query", "{\"q\":\"Paris\"}"))));
    assertEquals(
        expectedItem, input.get(1), "replayed function_call item must keep every official field");

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () ->
                encodeNextRequest(
                    replayState,
                    List.of(
                        new ProviderToolCallBlock(
                            new ProviderToolCall("call_1", "query", "{\"q\":\"Berlin\"}")))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }

  /** 测试意图：终态 reasoning 缺失的密文由流式事实补齐，保证可回放的原生推理不被丢失。 */
  @Test
  void streamedEncryptedReasoningIsStillRetainedInLosslessReplay() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), freezePrefixHash(), e -> {});
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
                + "\"output\":[{\"id\":\"rs_1\",\"type\":\"reasoning\",\"status\":\"completed\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"check the weather\"}]}]}}"));

    JsonNode replayItem = accumulator.replayState().payload().path("output").get(0);
    assertEquals("enc_stream", replayItem.path("encrypted_content").asText());
    assertEquals("completed", replayItem.path("status").asText());
  }

  /**
   * 测试意图：权威 item 未携带 arguments 时仍由流式参数增量补齐（最小修复），而它自身的 id/status 等官方字段继续无损保留， durable 工具调用语义保持一致。
   */
  @Test
  void functionCallArgumentsRepairedFromStreamKeepOfficialFields() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(userMessage())), createDescriptor(), freezePrefixHash(), e -> {});
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
    // 终态未声明 output：流式权威仍是 replay 事实来源
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
    ProviderReplayState replayState = accumulator.replayState();
    assertEquals(expectedItem, replayState.payload().path("output").get(0));

    ArrayNode input =
        encodeNextRequest(
            replayState,
            List.of(
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_1", "query", "{\"q\":\"Paris\"}"))));
    assertEquals(expectedItem, input.get(1));
  }

  /** 测试意图：结构损坏的已知 item 即使携带合法额外字段也仍然失败；未知 type 才是唯一的不透明通道。 */
  @Test
  void malformedKnownItemStillFailsEvenWithOfficialExtraFields() throws Exception {
    String prefixHash = freezePrefixHash();
    ObjectNode payload = MAPPER.createObjectNode();
    payload
        .putArray("output")
        .addObject()
        .put("type", "message")
        .put("role", "assistant")
        .put("id", "msg_1")
        .put("status", "completed")
        .put("content", "not_an_array");
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES,
            createDescriptor().affinity("gpt-5.4-mini"),
            prefixHash,
            payload);

    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> encodeNextRequest(replayState, List.of(new ProviderTextBlock("sunny"))));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
  }
}
