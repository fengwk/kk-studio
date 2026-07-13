package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider message、流增量和完整响应的结构化契约测试。 */
class ProviderContractTest {

  /** 消息 block 必须保留多模态输入、thinking、assistant tool call 和结构化 tool result。 */
  @Test
  void preservesStructuredMessageContentsAcrossRoles() {
    ProviderToolCall call = new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}");
    ProviderMessage user =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(
                new ProviderTextBlock("inspect this"),
                new ProviderImageBlock("image/png", "data:image/png;base64,AQID"),
                new ProviderAudioBlock("audio/wav", "artifact://audio-1")));
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("need file content"), new ProviderToolCallBlock(call)));
    ProviderMessage tool =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call-1", List.of(new ProviderTextBlock("content")), false, "{\"bytes\":7}")));

    assertEquals(3, user.contents().size());
    assertEquals(call, ((ProviderToolCallBlock) assistant.contents().get(1)).toolCall());
    assertEquals("call-1", ((ProviderToolResultBlock) tool.contents().get(0)).toolCallId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderToolCallBlock(call))));
  }

  /** Provider 可按 source index 分别发送 id、name 和参数片段，供 Agent 后续聚合和补齐。 */
  @Test
  void acceptsPartialToolCallDeltasAndRejectsGapsWithoutData() {
    List<ProviderStreamEvent.ToolCallDelta> deltas =
        List.of(
            new ProviderStreamEvent.ToolCallDelta(0, "call-1", null, null),
            new ProviderStreamEvent.ToolCallDelta(0, null, "read", null),
            new ProviderStreamEvent.ToolCallDelta(0, null, null, "{\"path\":\"README.md\"}"));

    assertEquals("call-1", deltas.get(0).id());
    assertEquals("read", deltas.get(1).name());
    assertEquals("{\"path\":\"README.md\"}", deltas.get(2).argumentsJson());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderStreamEvent.ToolCallDelta(0, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderStreamEvent.ToolCallDelta(-1, "call-1", null, null));
  }

  /** 完整响应必须携带非空 usage/cost，且 TOOL_CALLS 结束原因必须给出完整调用。 */
  @Test
  void requiresCompleteResponseAccountingAndToolCalls() {
    ModelUsage usage = new ModelUsage(1, 2, 0, 0, 0);
    ModelCost cost = new ModelCost("USD", BigDecimal.ZERO);

    assertThrows(
        NullPointerException.class,
        () -> new ProviderResponse("", "", List.of(), ProviderStopReason.COMPLETED, null, cost));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderResponse("", "", List.of(), ProviderStopReason.TOOL_CALLS, usage, cost));
  }
}
