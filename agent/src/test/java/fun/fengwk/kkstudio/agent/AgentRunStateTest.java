package fun.fengwk.kkstudio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import java.util.List;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AgentRunState 内部 gap 计算的边界测试。
 *
 * @author fengwk
 */
public class AgentRunStateTest {

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
    AssistantAttemptState attemptState = new AssistantAttemptState(new AgentRunContext());
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

  /** 校验 tool result complete 会补齐文本 suffix，并跳过已完整内容。 */
  @Test
  public void testToolContentGapCompletesTextSuffix() {
    ToolCall toolCall = new ToolCall();
    toolCall.setToolCallId("call_1");
    ToolExecutionState toolState = new ToolExecutionState(new AgentRunContext(), toolCall);
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
