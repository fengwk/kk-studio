package fun.fengwk.kkstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;

import java.util.List;

/**
 * AgentRunContext 关联状态对象的 gap 计算边界测试。
 *
 * @author fengwk
 */
public class AgentRunContextTest {

  /** 校验文本 gap 只补 suffix；当前内容不是完整内容前缀时使用完整内容修正。 */
  @Test
  public void testTextGapComputesSuffixOrReplacement() {
    assertEquals("lo", AgentTextDelta.gap("hel", "hello"));
    assertEquals("hello", AgentTextDelta.gap("bad", "hello"));
    assertEquals(null, AgentTextDelta.gap("hello", "hello"));
    assertEquals(null, AgentTextDelta.gap("hello", null));
  }

  /** 校验 assistant tool call complete 会补齐缺失字段与 arguments suffix。 */
  @Test
  public void testAssistantToolCallGapCompletesMissingFields() {
    AssistantAttemptState attemptState = new AssistantAttemptState();
    ToolCallDelta partial = new ToolCallDelta();
    partial.setArgumentsDelta("{\"text\":");
    IndexedToolCallDelta indexedPartial = new IndexedToolCallDelta();
    indexedPartial.setIndex(0);
    indexedPartial.setToolCallDelta(partial);
    attemptState.applyToolCallDelta(indexedPartial);

    ToolCall complete = new ToolCall();
    complete.setToolCallId("call_1");
    complete.setToolName("echo");
    complete.setArguments("{\"text\":\"OK\"}");

    List<IndexedToolCallDelta> gap = attemptState.computeToolCallGap(0, complete);

    assertEquals(1, gap.size());
    ToolCallDelta delta = gap.get(0).getToolCallDelta();
    assertEquals("call_1", delta.getToolCallId());
    assertEquals("echo", delta.getToolName());
    assertEquals("\"OK\"}", delta.getArgumentsDelta());
    assertTrue(attemptState.computeToolCallGap(0, complete).isEmpty());
  }

  /** 校验负 tool call index 不会写入累加状态，最终结果仍以合法 index 补齐。 */
  @Test
  public void testAssistantToolCallStateIgnoresNegativeIndex() {
    AssistantAttemptState attemptState = new AssistantAttemptState();
    ToolCallDelta partial = new ToolCallDelta();
    partial.setToolCallId("call_1");
    partial.setToolName("echo");
    partial.setArgumentsDelta("{}");
    IndexedToolCallDelta indexedPartial = new IndexedToolCallDelta();
    indexedPartial.setIndex(-1);
    indexedPartial.setToolCallDelta(partial);
    attemptState.applyToolCallDelta(indexedPartial);

    ToolCall complete = new ToolCall();
    complete.setToolCallId("call_1");
    complete.setToolName("echo");
    complete.setArguments("{}");

    List<IndexedToolCallDelta> gap = attemptState.computeToolCallGap(0, complete);
    assertEquals(1, gap.size());
    assertEquals(0, gap.get(0).getIndex());
    assertEquals(1, attemptState.toolCalls.size());
  }

  /** 校验 media complete 会补齐完整内容，非法 content delta 不会污染已有槽位。 */
  @Test
  public void testToolContentGapCompletesMediaAndIgnoresInvalidDeltas() {
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    ToolExecutionState toolState = new ToolExecutionState(toolCall);
    ToolContent complete = new ToolContent();
    complete.setType(ToolContentType.image);
    complete.setData("base64-data");
    complete.setMime("image/png");
    complete.setName("chart.png");

    List<IndexedToolContentDelta> gap = toolState.computeGap(List.of(complete));

    assertEquals(1, gap.size());
    assertEquals(ToolContentType.image, gap.get(0).getContentDelta().getType());
    assertEquals("base64-data", gap.get(0).getContentDelta().getData());

    IndexedToolContentDelta invalidIndex = new IndexedToolContentDelta();
    invalidIndex.setIndex(-1);
    invalidIndex.setContentDelta(gap.get(0).getContentDelta());
    IndexedToolContentDelta missingType = new IndexedToolContentDelta();
    missingType.setIndex(0);
    missingType.setContentDelta(new ToolContentDelta());
    toolState.applyContentDeltas(List.of(invalidIndex, missingType, gap.get(0)));

    assertTrue(toolState.computeGap(List.of(complete)).isEmpty());
  }

  /** 校验 tool result complete 会补齐文本 suffix，并跳过已完整内容。 */
  @Test
  public void testToolContentGapCompletesTextSuffix() {
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    ToolExecutionState toolState = new ToolExecutionState(toolCall);
    ToolContentDelta partial = new ToolContentDelta();
    partial.setType(ToolContentType.text);
    partial.setText("hel");
    IndexedToolContentDelta indexedPartial = new IndexedToolContentDelta();
    indexedPartial.setIndex(0);
    indexedPartial.setContentDelta(partial);
    toolState.applyContentDeltas(List.of(indexedPartial));

    ToolContent complete = new ToolContent();
    complete.setType(ToolContentType.text);
    complete.setText("hello");

    List<IndexedToolContentDelta> gap = toolState.computeGap(List.of(complete));

    assertEquals(1, gap.size());
    assertEquals("lo", gap.get(0).getContentDelta().getText());
    assertTrue(toolState.computeGap(List.of(complete)).isEmpty());
  }
}
