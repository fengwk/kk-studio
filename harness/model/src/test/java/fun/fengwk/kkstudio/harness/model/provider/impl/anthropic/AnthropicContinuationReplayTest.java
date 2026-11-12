package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 Anthropic 续写终止态（{@code pause_turn} / {@code compaction}）到运行时 CONTINUE 的映射，以及 compaction replay
 * 对历史的重写：compaction block 必须唯一且位于消息首部，被摘要的历史整体省略；replay 只与 affinity 绑定，与 system 指令 和更早的历史无关。
 */
class AnthropicContinuationReplayTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

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

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest);
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

    List<ProviderContentBlock> blocks = completion.response().blocks();
    assertEquals(2, blocks.size());
    assertEquals("server_tool_use", ((ProviderOpaqueBlock) blocks.get(0)).blockType());
    assertEquals(
        "kk",
        ((ProviderOpaqueBlock) blocks.get(0))
            .providerBlockData()
            .nativeFields()
            .path("input")
            .path("query")
            .asString(""));
    assertEquals("Searching", ((ProviderTextBlock) blocks.get(1)).text());
    assertEquals(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        ((ProviderOpaqueBlock) blocks.get(0)).providerBlockData().protocol());

    // 下一轮：CONTINUE 的 native replay 必须原样进入 wire
    ProviderRequest nextRequest = request(List.of(userMsg("search the web"), assistantMsg(blocks)));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(2, assistantContent.size());
    assertEquals("server_tool_use", assistantContent.get(0).path("type").asString(""));
    assertEquals("kk", assistantContent.get(0).path("input").path("query").asString(""));
  }

  /** 测试意图：{@code compaction} 同样映射为 CONTINUE；即使没有 native block，也必须有 replay state 供续写。 */
  @Test
  void mapsCompactionToContinueAndFreezesReplayState() {
    ProviderRequest firstRequest = request(List.of(userMsg("summarize")));

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}");
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
    assertEquals(1, completion.response().blocks().size());
    assertEquals("state", ((ProviderTextBlock) completion.response().blocks().get(0)).text());
  }

  /**
   * 测试意图：compaction replay 代表历史已被摘要——编码时先按原 durable 前缀完成校验，再清空已编码消息，使 compaction assistant message
   * 成为首条消息、被摘要历史整体省略，其后的消息按序保留。
   */
  @Test
  void compactionReplayOmitsSummarizedHistoryAndStartsMessages() throws IOException {
    // 原 durable 前缀：仅含首条 user 消息（compaction 之前的真实历史）
    ObjectNode compactionBlock = NODES.objectNode();
    compactionBlock.put("type", "compaction");
    compactionBlock.put("summary", "earlier turns were summarized");
    compactionBlock.put("signature", "sig-compaction");
    ProviderOpaqueBlock compaction = compactionBlock(compactionBlock);

    ProviderRequest continuationRequest =
        request(
            List.of(
                userMsg("old question"),
                assistantMsg(List.of(compaction)),
                userMsg("new question")));
    JsonNode messages = wire(encoder.encode(continuationRequest, descriptor)).path("messages");

    // 被摘要的历史（user: old question）必须整体省略，compaction assistant message 是首条消息
    assertEquals(2, messages.size());
    assertEquals("assistant", messages.get(0).path("role").asString(""));
    assertEquals(1, messages.get(0).path("content").size());
    assertEquals(compactionBlock, messages.get(0).path("content").get(0));
    assertEquals("user", messages.get(1).path("role").asString(""));
    assertEquals("new question", messages.get(1).path("content").get(0).path("text").asString(""));
  }

  /**
   * 测试意图：compaction replay 只与 affinity 绑定，与 system 指令和更早的历史无关：即使 system 指令与 前缀历史都发生变化，replay
   * 仍生效并省略被摘要的历史。
   */
  @Test
  void compactionReplayAppliesAcrossChangedSystemAndHistory() throws IOException {
    ObjectNode compactionBlock = NODES.objectNode();
    compactionBlock.put("type", "compaction");
    compactionBlock.put("summary", "summarized");
    ProviderOpaqueBlock compaction = compactionBlock(compactionBlock);

    // 与录制时不同的 system 指令，且首条 user 消息也被替换；replay 不受影响
    ProviderRequest continuationRequest =
        request(
            "A completely different system instruction.",
            List.of(
                userMsg("unrelated earlier question"),
                assistantMsg(List.of(compaction)),
                userMsg("new question")));
    JsonNode messages = wire(encoder.encode(continuationRequest, descriptor)).path("messages");

    assertEquals(2, messages.size());
    assertEquals("assistant", messages.get(0).path("role").asString(""));
    assertEquals(compactionBlock, messages.get(0).path("content").get(0));
    assertEquals("new question", messages.get(1).path("content").get(0).path("text").asString(""));
  }

  /** 测试意图：compaction block 必须唯一且位于消息首部，否则显式失败而不是编码出语义错误的请求。 */
  @Test
  void rejectsDuplicateOrMisplacedCompactionBlock() {
    ProviderOpaqueBlock first = compactionBlock("a");
    ProviderOpaqueBlock second = compactionBlock("b");
    ProviderException duplicateError = assertRejected(List.of(first, second));
    assertEquals("duplicate compaction block in replay payload", duplicateError.getMessage());

    ProviderException misplacedError =
        assertRejected(List.of(new ProviderTextBlock("before"), compactionBlock("s")));
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

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}");
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

  private ProviderException assertRejected(List<ProviderContentBlock> blocks) {
    ProviderRequest request = request(List.of(userMsg("hi"), assistantMsg(blocks)));
    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    return error;
  }

  private ProviderOpaqueBlock compactionBlock(String summary) {
    ObjectNode fields = NODES.objectNode();
    fields.put("summary", summary);
    return new ProviderOpaqueBlock(
        "compaction",
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            fields));
  }

  private ProviderOpaqueBlock compactionBlock(ObjectNode rawBlock) {
    ObjectNode fields = rawBlock.deepCopy();
    fields.remove("type");
    return new ProviderOpaqueBlock(
        "compaction",
        new ProviderBlockData(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            fields));
  }

  private AnthropicStreamAccumulator accumulator(ProviderRequest request) {
    return new AnthropicStreamAccumulator(
        request, descriptor, new ProviderStreamBridge(new NoopHandler()));
  }

  private static JsonNode wire(AnthropicEncodedRequest encoded) throws IOException {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return request("Test system instruction.", messages);
  }

  private ProviderRequest request(String systemInstruction, List<ProviderMessage> messages) {
    return new ProviderRequest(
        model(),
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        systemInstruction,
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderMessage userMsg(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistantMsg(List<ProviderContentBlock> blocks) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks);
  }

  private static ModelDescriptor model() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-5-sonnet",
        "claude-3-5-sonnet",
        Set.of(ModelInputModality.TEXT),
        true,
        false);
  }

  private static final class NoopHandler implements ProviderStreamHandler {

    @Override
    public void onEvent(ProviderStreamEvent event) {}

    @Override
    public void onComplete(ProviderCompletion completion) {}

    @Override
    public void onError(ProviderException error) {}
  }
}
