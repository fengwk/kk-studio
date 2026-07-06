package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

import java.util.Objects;

/** 把正文中的 <think>...</think> 兜底拆到 thinking 通道，避免泄漏给用户可见文本。 */
final class ThinkTagExtractingAssistantResponseHandler implements AssistantResponseHandler {

  private final AssistantResponseHandler delegate;
  private final ThinkTagStreamSplitter splitter = new ThinkTagStreamSplitter();
  private final StringBuilder extractedThinking = new StringBuilder();
  private boolean sawStructuredThinking;

  ThinkTagExtractingAssistantResponseHandler(AssistantResponseHandler delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  @Override
  public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
    ThinkTagStreamSplitter.SplitChunk splitChunk = splitter.append(textDelta);
    if (!splitChunk.text().isEmpty()) {
      delegate.onTextDelta(splitChunk.text(), handle);
    }
    if (!splitChunk.thinking().isEmpty()) {
      extractedThinking.append(splitChunk.thinking());
    }
  }

  @Override
  public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
    if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
      sawStructuredThinking = true;
    }
    delegate.onThinkingDelta(thinkingDelta, handle);
  }

  @Override
  public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
    delegate.onToolCallDelta(toolCallDelta, handle);
  }

  @Override
  public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
    delegate.onToolCallComplete(index, toolCall, handle);
  }

  @Override
  public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
    ThinkTagStreamSplitter.SplitChunk tail = splitter.finish();
    if (!tail.text().isEmpty()) {
      delegate.onTextDelta(tail.text(), handle);
    }
    if (!tail.thinking().isEmpty()) {
      extractedThinking.append(tail.thinking());
    }

    String extractedThinkingText = emptyToNull(extractedThinking.toString());
    if (!sawStructuredThinking && extractedThinkingText != null) {
      delegate.onThinkingDelta(extractedThinkingText, handle);
    }

    if (response == null) {
      delegate.onComplete(null, handle);
      return;
    }

    ThinkTagStreamSplitter.SplitChunk normalized = ThinkTagStreamSplitter.parse(response.getText());
    String normalizedText = emptyToNull(normalized.text());
    String normalizedThinking =
        hasText(response.getThinking())
            ? response.getThinking()
            : emptyToNull(normalized.thinking());
    if (Objects.equals(response.getText(), normalizedText)
        && Objects.equals(response.getThinking(), normalizedThinking)) {
      delegate.onComplete(response, handle);
      return;
    }

    delegate.onComplete(
        AssistantResponse.builder()
            .text(normalizedText)
            .thinking(normalizedThinking)
            .toolCalls(response.getToolCalls())
            .metadata(response.getMetadata())
            .build(),
        handle);
  }

  @Override
  public void onError(Throwable error, AssistantResponseHandle handle) {
    delegate.onError(error, handle);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  static final class ThinkTagStreamSplitter {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StringBuilder carry = new StringBuilder();
    private boolean inThinking;
    private boolean suppressLeadingWhitespace;

    SplitChunk append(String chunk) {
      if (chunk == null || chunk.isEmpty()) {
        return SplitChunk.EMPTY;
      }
      carry.append(chunk);
      StringBuilder text = new StringBuilder();
      StringBuilder thinking = new StringBuilder();
      drain(text, thinking, false);
      return new SplitChunk(text.toString(), thinking.toString());
    }

    SplitChunk finish() {
      StringBuilder text = new StringBuilder();
      StringBuilder thinking = new StringBuilder();
      drain(text, thinking, true);
      return new SplitChunk(text.toString(), thinking.toString());
    }

    static SplitChunk parse(String text) {
      ThinkTagStreamSplitter splitter = new ThinkTagStreamSplitter();
      SplitChunk first = splitter.append(text);
      SplitChunk second = splitter.finish();
      return new SplitChunk(first.text() + second.text(), first.thinking() + second.thinking());
    }

    private void drain(StringBuilder text, StringBuilder thinking, boolean flushAll) {
      while (carry.length() > 0) {
        if (inThinking) {
          int closeIndex = carry.indexOf(CLOSE_TAG);
          if (closeIndex >= 0) {
            thinking.append(carry, 0, closeIndex);
            carry.delete(0, closeIndex + CLOSE_TAG.length());
            inThinking = false;
            suppressLeadingWhitespace = true;
            continue;
          }
          int keep = flushAll ? 0 : longestTagPrefixSuffix(CLOSE_TAG);
          int emitLength = carry.length() - keep;
          if (emitLength <= 0) {
            break;
          }
          thinking.append(carry, 0, emitLength);
          carry.delete(0, emitLength);
          continue;
        }

        int openIndex = carry.indexOf(OPEN_TAG);
        if (openIndex >= 0) {
          appendText(text, carry.substring(0, openIndex));
          carry.delete(0, openIndex + OPEN_TAG.length());
          inThinking = true;
          continue;
        }
        int keep = flushAll ? 0 : longestTagPrefixSuffix(OPEN_TAG);
        int emitLength = carry.length() - keep;
        if (emitLength <= 0) {
          break;
        }
        appendText(text, carry.substring(0, emitLength));
        carry.delete(0, emitLength);
      }
    }

    private void appendText(StringBuilder text, String value) {
      if (value == null || value.isEmpty()) {
        return;
      }
      String normalized = value;
      if (suppressLeadingWhitespace) {
        int index = 0;
        while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) {
          index++;
        }
        if (index == normalized.length()) {
          return;
        }
        normalized = normalized.substring(index);
        suppressLeadingWhitespace = false;
      }
      text.append(normalized);
    }

    private int longestTagPrefixSuffix(String tag) {
      int max = Math.min(carry.length(), tag.length() - 1);
      for (int len = max; len > 0; len--) {
        boolean match = true;
        for (int i = 0; i < len; i++) {
          if (carry.charAt(carry.length() - len + i) != tag.charAt(i)) {
            match = false;
            break;
          }
        }
        if (match) {
          return len;
        }
      }
      return 0;
    }

    record SplitChunk(String text, String thinking) {

      private static final SplitChunk EMPTY = new SplitChunk("", "");
    }
  }
}
