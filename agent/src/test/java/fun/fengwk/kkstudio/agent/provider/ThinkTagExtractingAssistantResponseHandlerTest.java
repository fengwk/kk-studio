package fun.fengwk.kkstudio.agent.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * @author fengwk
 */
public class ThinkTagExtractingAssistantResponseHandlerTest {

  @Test
  public void testParseLeavesPlainTextUntouched() {
    ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.SplitChunk split =
        ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.parse("plain text");

    assertEquals("plain text", split.text());
    assertEquals("", split.thinking());
  }

  @Test
  public void testParseExtractsThinkTagAndTrimsWhitespaceAfterCloseTag() {
    ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.SplitChunk split =
        ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.parse(
            "<think>internal</think>\n\nfinal answer");

    assertEquals("final answer", split.text());
    assertEquals("internal", split.thinking());
  }

  @Test
  public void testParseSupportsMultipleThinkBlocks() {
    ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.SplitChunk split =
        ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.parse(
            "A<think>x</think>\nB<think>y</think> C");

    assertEquals("ABC", split.text());
    assertEquals("xy", split.thinking());
  }

  @Test
  public void testParseKeepsStrayCloseTagAsVisibleText() {
    ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.SplitChunk split =
        ThinkTagExtractingAssistantResponseHandler.ThinkTagStreamSplitter.parse(
            "before</think>after");

    assertEquals("before</think>after", split.text());
    assertEquals("", split.thinking());
  }

  @Test
  public void testHandlerExtractsThinkTagsAcrossChunkBoundaries() {
    CapturingHandler delegate = new CapturingHandler();
    ThinkTagExtractingAssistantResponseHandler handler =
        new ThinkTagExtractingAssistantResponseHandler(delegate);
    TestAssistantResponseHandle responseHandle = new TestAssistantResponseHandle();

    handler.onTextDelta("<thi", responseHandle);
    handler.onTextDelta("nk>alpha", responseHandle);
    handler.onTextDelta("</th", responseHandle);
    handler.onTextDelta("ink>\n\nbe", responseHandle);
    handler.onTextDelta("ta", responseHandle);
    handler.onComplete(
        AssistantResponse.builder()
            .text("<think>alpha</think>\n\nbeta")
            .toolCalls(List.of())
            .build(),
        responseHandle);

    assertEquals("beta", delegate.joinedText());
    assertEquals(List.of("alpha"), delegate.thinkingDeltas);
    assertNotNull(delegate.response);
    assertEquals("beta", delegate.response.getText());
    assertEquals("alpha", delegate.response.getThinking());
  }

  @Test
  public void testHandlerTreatsUnclosedThinkBlockAsThinkingOnComplete() {
    CapturingHandler delegate = new CapturingHandler();
    ThinkTagExtractingAssistantResponseHandler handler =
        new ThinkTagExtractingAssistantResponseHandler(delegate);
    TestAssistantResponseHandle responseHandle = new TestAssistantResponseHandle();

    handler.onTextDelta("prefix<think>draft reasoning", responseHandle);
    handler.onComplete(
        AssistantResponse.builder()
            .text("prefix<think>draft reasoning")
            .toolCalls(List.of())
            .build(),
        responseHandle);

    assertEquals("prefix", delegate.joinedText());
    assertEquals(List.of("draft reasoning"), delegate.thinkingDeltas);
    assertNotNull(delegate.response);
    assertEquals("prefix", delegate.response.getText());
    assertEquals("draft reasoning", delegate.response.getThinking());
  }

  @Test
  public void testHandlerDoesNotDuplicateExtractedThinkingWhenStructuredThinkingAlreadyExists() {
    CapturingHandler delegate = new CapturingHandler();
    ThinkTagExtractingAssistantResponseHandler handler =
        new ThinkTagExtractingAssistantResponseHandler(delegate);
    TestAssistantResponseHandle responseHandle = new TestAssistantResponseHandle();

    handler.onTextDelta("<think>hidden</think>\n\nhello", responseHandle);
    handler.onThinkingDelta("structured", responseHandle);
    handler.onComplete(
        AssistantResponse.builder()
            .text("hello")
            .thinking("structured")
            .toolCalls(List.of())
            .build(),
        responseHandle);

    assertEquals("hello", delegate.joinedText());
    assertEquals(List.of("structured"), delegate.thinkingDeltas);
    assertNotNull(delegate.response);
    assertEquals("hello", delegate.response.getText());
    assertEquals("structured", delegate.response.getThinking());
  }

  private static final class CapturingHandler implements AssistantResponseHandler {

    private final List<String> textDeltas = new ArrayList<>();
    private final List<String> thinkingDeltas = new ArrayList<>();
    private AssistantResponse response;
    private Throwable error;

    @Override
    public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
      textDeltas.add(textDelta);
    }

    @Override
    public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
      thinkingDeltas.add(thinkingDelta);
    }

    @Override
    public void onToolCallDelta(
        IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {}

    @Override
    public void onToolCallComplete(
        Integer index, ToolCall toolCall, AssistantResponseHandle handle) {}

    @Override
    public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
      this.response = response;
    }

    @Override
    public void onError(Throwable error, AssistantResponseHandle handle) {
      this.error = error;
    }

    private String joinedText() {
      return String.join("", textDeltas);
    }
  }

  private static final class TestAssistantResponseHandle implements AssistantResponseHandle {

    @Override
    public void cancel() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }
}
