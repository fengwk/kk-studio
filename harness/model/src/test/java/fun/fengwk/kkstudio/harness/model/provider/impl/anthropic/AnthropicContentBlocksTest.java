package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderProtocolEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试意图：锁定 indexed content block 状态机的拒绝边界——非法 index、未知或乱序 block、delta 与 block 类型不配对、签名/引用形状非法都
 * 显式失败；原生 block 只在 input 为空占位时接受 input_json_delta。
 */
class AnthropicContentBlocksTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final List<ProviderStreamEvent> emitted = new ArrayList<>();
  private final AnthropicContentBlocks blocks = new AnthropicContentBlocks(bridge(emitted));

  /** 安静 handler：只收集 normalized 增量，其余回调不关心。 */
  private static ProviderStreamBridge bridge(List<ProviderStreamEvent> sink) {
    return new ProviderStreamBridge(
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {
            sink.add(event);
          }

          @Override
          public void onProtocolEvent(ProviderProtocolEvent event) {}

          @Override
          public void onRequestBody(byte[] body) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {}
        });
  }

  private static AnthropicContentBlocks quietAccumulator() {
    return new AnthropicContentBlocks(bridge(new ArrayList<>()));
  }

  private static ObjectNode json(String text) {
    try {
      return (ObjectNode) MAPPER.readTree(text);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static ProviderException rejected(Runnable action) {
    ProviderException failure = assertThrows(ProviderException.class, action::run);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, failure.kind());
    return failure;
  }

  /** block 的起始 index 必须是非负且严格递增的整数。 */
  @Test
  void rejectsInvalidAndNonIncreasingBlockIndexes() {
    rejected(() -> blocks.start(json("{\"content_block\":{\"type\":\"text\"}}")));
    rejected(() -> blocks.start(json("{\"index\":\"0\",\"content_block\":{\"type\":\"text\"}}")));
    rejected(() -> blocks.start(json("{\"index\":-1,\"content_block\":{\"type\":\"text\"}}")));

    blocks.start(json("{\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"a\"}}"));
    rejected(() -> blocks.start(json("{\"index\":2,\"content_block\":{\"type\":\"text\"}}")));
    rejected(() -> blocks.start(json("{\"index\":1,\"content_block\":{\"type\":\"text\"}}")));
  }

  /** content_block 必须带 string type；未知 type 保留为 opaque，不产生 normalized 事件。 */
  @Test
  void keepsUnknownBlockTypesAsOpaque() {
    rejected(() -> blocks.start(json("{\"index\":0,\"content_block\":{}}")));
    // 无 type 的 block 被拒绝时已消费 index 0，后续用例使用 index 1。
    rejected(() -> blocks.start(json("{\"index\":1,\"content_block\":{\"type\":1}}")));

    blocks.start(json("{\"index\":2,\"content_block\":{\"type\":\"server_tool_use\"}}"));
    assertTrue(emitted.isEmpty());
    assertEquals("server_tool_use", blocks.ordered().iterator().next().type);
  }

  /** 各 normalized block 的已知字段类型非法时显式失败。 */
  @Test
  void rejectsInvalidKnownFieldsOnStart() {
    rejected(
        () -> blocks.start(json("{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":1}}")));
    rejected(
        () ->
            blocks.start(
                json("{\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":1}}")));
    rejected(
        () ->
            blocks.start(
                json("{\"index\":0,\"content_block\":{\"type\":\"thinking\",\"signature\":1}}")));
    rejected(
        () ->
            blocks.start(
                json(
                    "{\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":1}}")));
    rejected(
        () ->
            blocks.start(
                json("{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"name\":\"t\"}}")));
    rejected(
        () ->
            blocks.start(
                json(
                    "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\" \","
                        + "\"name\":\"t\"}}")));
    rejected(
        () ->
            blocks.start(
                json(
                    "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c\","
                        + "\"name\":\"t\",\"input\":1}}")));
  }

  /** start 自带 citations 时必须是非空对象数组。 */
  @Test
  void rejectsInvalidCitationShapesOnStart() {
    rejected(
        () ->
            blocks.start(
                json("{\"index\":0,\"content_block\":{\"type\":\"text\",\"citations\":{}}}")));
    rejected(
        () ->
            blocks.start(
                json(
                    "{\"index\":0,\"content_block\":{\"type\":\"text\"," + "\"citations\":[1]}}")));
  }

  /** tool_use 的空 input 占位仍会发出一条 ToolCallDelta，且没有参数。 */
  @Test
  void emitsToolCallDeltaForEmptyInputPlaceholder() {
    blocks.start(
        json(
            "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c1\","
                + "\"name\":\"read\",\"input\":{}}}"));
    assertEquals(1, emitted.size());
    assertTrue(emitted.get(0) instanceof ProviderStreamEvent.ToolCallDelta);
  }

  /** delta 的 index 必须合法且指向一个还处于 ACTIVE 的 block。 */
  @Test
  void rejectsDeltaForUnknownOrStoppedBlock() {
    rejected(() -> blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"text_delta\"}}")));

    blocks.start(json("{\"index\":0,\"content_block\":{\"type\":\"text\"}}"));
    rejected(() -> blocks.delta(json("{\"index\":\"0\",\"delta\":{\"type\":\"text_delta\"}}")));
    rejected(() -> blocks.delta(json("{\"index\":0}")));
    rejected(() -> blocks.delta(json("{\"index\":0,\"delta\":{}}")));

    blocks.stop(json("{\"index\":0}"));
    rejected(() -> blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"text_delta\"}}")));
  }

  /** text block 只接受 text_delta 与 citations_delta。 */
  @Test
  void rejectsMismatchedTextDeltas() {
    blocks.start(json("{\"index\":0,\"content_block\":{\"type\":\"text\"}}"));
    rejected(
        () -> blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":1}}")));
    rejected(
        () ->
            blocks.delta(
                json("{\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":1}}")));
    rejected(() -> blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"thinking_delta\"}}")));

    blocks.delta(
        json(
            "{\"index\":0,\"delta\":{\"type\":\"citations_delta\","
                + "\"citation\":{\"type\":\"page\"}}}"));
    assertEquals(1, blocks.ordered().iterator().next().citations.size());
  }

  /** thinking block 的 delta 必须与 thinking/signature 配对，签名逐段累加。 */
  @Test
  void accumulatesThinkingAndSignatureDeltas() {
    blocks.start(json("{\"index\":0,\"content_block\":{\"type\":\"thinking\"}}"));
    rejected(
        () ->
            blocks.delta(
                json("{\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":1}}")));
    rejected(
        () ->
            blocks.delta(
                json("{\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":1}}")));
    rejected(() -> blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"text_delta\"}}")));

    blocks.delta(json("{\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"a\"}}"));
    blocks.delta(
        json("{\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"s1\"}}"));
    blocks.delta(
        json("{\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"s2\"}}"));

    AnthropicWireBlock block = blocks.ordered().iterator().next();
    assertEquals("a", block.thinkingBuffer.toString());
    assertEquals("s1s2", block.signature);
  }

  /** redacted_thinking 必须完整，不接受任何 delta。 */
  @Test
  void rejectsDeltasForRedactedThinking() {
    AnthropicContentBlocks fresh = quietAccumulator();
    fresh.start(
        json(
            "{\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}}"));
    rejected(() -> fresh.delta(json("{\"index\":0,\"delta\":{\"type\":\"text_delta\"}}")));
  }

  /** 原生 block 的 input_json_delta 只能累积在空占位上；非 input_json_delta 一律拒绝。 */
  @Test
  void rejectsNativeBlockDeltasThatCannotBeAssembled() {
    blocks.start(
        json(
            "{\"index\":0,\"content_block\":{\"type\":\"server_tool_use\","
                + "\"input\":{\"x\":1}}}"));
    rejected(
        () ->
            blocks.delta(
                json(
                    "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"}}")));
  }

  /** 未闭合的 block 会被 allStopped 报告，只有 stop 之后才变为闭合。 */
  @Test
  void reportsStopStatePerBlock() {
    assertTrue(blocks.allStopped());
    blocks.start(json("{\"index\":0,\"content_block\":{\"type\":\"text\"}}"));
    assertFalse(blocks.allStopped());
    blocks.stop(json("{\"index\":0}"));
    assertTrue(blocks.allStopped());
    rejected(() -> blocks.stop(json("{\"index\":0}")));
  }
}
