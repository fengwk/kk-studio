package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 Anthropic 续写终止态（{@code pause_turn} / {@code compaction}）到运行时 CONTINUE 的映射，以及 compaction replay
 * 对历史的重写：compaction block 必须唯一且位于消息首部，被摘要的历史整体省略，前缀哈希仍按原 durable 前缀校验。
 */
class AnthropicContinuationReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String FORGED_PREFIX_HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  /**
   * 测试意图：{@code pause_turn} 映射为 CONTINUE 并冻结 native replay state（server tool 的输入与结果原样保留），
   * 供运行时下一轮原样回放。
   */
  @Test
  void mapsPauseTurnToContinueWithNativeReplayState() throws IOException {
    ProviderRequest firstRequest = request(List.of(userMsg("search the web")));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_pause\",\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{}}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"kk\\\"}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Searching\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"},\"usage\":{\"output_tokens\":2}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.CONTINUE, completion.response().stopReason());
    // CONTINUE 不得携带 normalized 工具意图
    assertTrue(completion.response().toolCalls().isEmpty());

    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState, "CONTINUE must freeze native replay state");
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, replayState.format());
    assertEquals(frozenHash, replayState.sourcePrefixHash());
    JsonNode content = replayState.payload().path("content");
    assertEquals(2, content.size());
    assertEquals("server_tool_use", content.get(0).path("type").asText());
    assertEquals("kk", content.get(0).path("input").path("query").asText());
    assertEquals("Searching", content.get(1).path("text").asText());

    // 下一轮：CONTINUE 的 native replay 必须原样进入 wire
    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg("search the web"),
                assistantMsg(List.of(new ProviderTextBlock("Searching")), replayState)));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(2, assistantContent.size());
    assertEquals("server_tool_use", assistantContent.get(0).path("type").asText());
    assertEquals("kk", assistantContent.get(0).path("input").path("query").asText());
  }

  /** 测试意图：{@code compaction} 同样映射为 CONTINUE；即使没有 native block，也必须有 replay state 供续写。 */
  @Test
  void mapsCompactionToContinueAndFreezesReplayState() {
    ProviderRequest firstRequest = request(List.of(userMsg("summarize")));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"state\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"compaction\"},\"usage\":{\"output_tokens\":1}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.CONTINUE, completion.response().stopReason());
    assertNotNull(completion.replayState());
    assertEquals(
        "state", completion.replayState().payload().path("content").get(0).path("text").asText());
  }

  /**
   * 测试意图：compaction replay 代表历史已被摘要——编码时先按原 durable 前缀完成校验，再清空已编码消息，使 compaction assistant message
   * 成为首条消息、被摘要历史整体省略，其后的消息按序保留。
   */
  @Test
  void compactionReplayOmitsSummarizedHistoryAndStartsMessages() throws IOException {
    // 原 durable 前缀：仅含首条 user 消息（compaction 之前的真实历史）
    String durablePrefixHash =
        encoder.encode(request(List.of(userMsg("old question"))), descriptor).sourcePrefixHash();

    ObjectNode compactionBlock = NODES.objectNode();
    compactionBlock.put("type", "compaction");
    compactionBlock.put("summary", "earlier turns were summarized");
    compactionBlock.put("signature", "sig-compaction");
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            durablePrefixHash,
            payload(compactionBlock));

    ProviderRequest continuationRequest =
        request(
            List.of(
                userMsg("old question"),
                assistantMsg(List.of(new ProviderTextBlock("")), replayState),
                userMsg("new question")));
    JsonNode messages = wire(encoder.encode(continuationRequest, descriptor)).path("messages");

    // 被摘要的历史（user: old question）必须整体省略，compaction assistant message 是首条消息
    assertEquals(2, messages.size());
    assertEquals("assistant", messages.get(0).path("role").asText());
    assertEquals(1, messages.get(0).path("content").size());
    assertEquals(compactionBlock, messages.get(0).path("content").get(0));
    assertEquals("user", messages.get(1).path("role").asText());
    assertEquals("new question", messages.get(1).path("content").get(0).path("text").asText());
  }

  /** 测试意图：compaction replay 的前缀校验仍针对原 durable 前缀；失配时 fail closed 而不是丢掉 compaction block。 */
  @Test
  void compactionReplayFailsClosedOnPrefixHashMismatch() {
    ObjectNode compactionBlock = NODES.objectNode();
    compactionBlock.put("type", "compaction");
    compactionBlock.put("summary", "summarized");
    ProviderReplayState forgedReplay =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            FORGED_PREFIX_HASH,
            payload(compactionBlock));

    ProviderRequest continuationRequest =
        request(
            List.of(
                userMsg("old question"),
                assistantMsg(List.of(new ProviderTextBlock("")), forgedReplay)));
    ProviderException error =
        assertThrows(
            ProviderException.class, () -> encoder.encode(continuationRequest, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        error.getMessage());
  }

  /** 测试意图：compaction block 必须唯一且位于消息首部，否则显式失败而不是编码出语义错误的请求。 */
  @Test
  void rejectsDuplicateOrMisplacedCompactionBlock() {
    String durablePrefixHash =
        encoder.encode(request(List.of(userMsg("hi"))), descriptor).sourcePrefixHash();

    // 1. 两个 compaction block
    ObjectNode duplicated = NODES.objectNode();
    duplicated.put("role", "assistant");
    ArrayNode duplicatedContent = duplicated.putArray("content");
    duplicatedContent.addObject().put("type", "compaction").put("summary", "a");
    duplicatedContent.addObject().put("type", "compaction").put("summary", "b");
    ProviderException duplicateError =
        assertRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("claude-3-5-sonnet"),
                durablePrefixHash,
                duplicated));
    assertEquals("duplicate compaction block in replay payload", duplicateError.getMessage());

    // 2. compaction block 不在首位
    ObjectNode misplaced = NODES.objectNode();
    misplaced.put("role", "assistant");
    ArrayNode misplacedContent = misplaced.putArray("content");
    misplacedContent.addObject().put("type", "text").put("text", "before");
    misplacedContent.addObject().put("type", "compaction").put("summary", "s");
    ProviderException misplacedError =
        assertRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("claude-3-5-sonnet"),
                durablePrefixHash,
                misplaced));
    assertEquals(
        "compaction block must be the first content block of the replay message",
        misplacedError.getMessage());
  }

  /**
   * 测试意图：CONTINUE 是撤下工具意图的续写终止态，normalized tool call 与其不可共存；上游若在 pause_turn 中下发 client
   * tool_use，必须显式失败而不是制造出运行时不接受的响应。
   */
  @Test
  void rejectsToolUseUnderContinueStopReason() {
    ProviderRequest firstRequest = request(List.of(userMsg("hi")));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"calc\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"},\"usage\":{\"output_tokens\":1}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("CONTINUE response must not contain tool calls", error.getMessage());
  }

  private ProviderException assertRejected(ProviderReplayState replayState) {
    ProviderRequest request =
        request(
            List.of(userMsg("hi"), assistantMsg(List.of(new ProviderTextBlock("")), replayState)));
    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    return error;
  }

  /** 构造只含 role + content 的 assistant replay payload。 */
  private static ObjectNode payload(ObjectNode firstBlock) {
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    payload.putArray("content").add(firstBlock);
    return payload;
  }

  private AnthropicStreamAccumulator accumulator(
      ProviderRequest request, String frozenSourcePrefixHash) {
    return new AnthropicStreamAccumulator(
        request, descriptor, frozenSourcePrefixHash, new AnthropicStreamBridge(new NoopHandler()));
  }

  private static JsonNode wire(AnthropicEncodedRequest encoded) throws IOException {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return new ProviderRequest(
        model(),
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderMessage userMsg(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistantMsg(
      List<ProviderContentBlock> blocks, ProviderReplayState replayState) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks, replayState);
  }

  private static ModelDescriptor model() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-5-sonnet",
        "claude-3-5-sonnet",
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        new ModelPricing(
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
            BigDecimal.ZERO));
  }

  private static final class NoopHandler implements ProviderStreamHandler {

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

    @Override
    public void onError(ProviderException error, ProviderStream stream) {}
  }
}
