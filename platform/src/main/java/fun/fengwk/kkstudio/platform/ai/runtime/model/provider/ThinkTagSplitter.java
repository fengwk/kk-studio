package fun.fengwk.kkstudio.platform.ai.runtime.model.provider;

/** 将 OpenAI 兼容端点错误混入正文的 {@code <think>} 块拆为 reasoning 内容。 */
final class ThinkTagSplitter {

  private static final String OPEN_TAG = "<think>";
  private static final String CLOSE_TAG = "</think>";

  private final StringBuilder carry = new StringBuilder();
  private boolean inThinking;
  private boolean suppressLeadingWhitespace;

  Split append(String chunk) {
    if (chunk == null || chunk.isEmpty()) {
      return Split.EMPTY;
    }
    carry.append(chunk);
    return drain(false);
  }

  Split finish() {
    return drain(true);
  }

  static Split parse(String text) {
    ThinkTagSplitter splitter = new ThinkTagSplitter();
    Split first = splitter.append(text);
    Split second = splitter.finish();
    return new Split(first.text() + second.text(), first.thinking() + second.thinking());
  }

  private Split drain(boolean flush) {
    StringBuilder text = new StringBuilder();
    StringBuilder thinking = new StringBuilder();
    while (!carry.isEmpty()) {
      if (inThinking) {
        int close = carry.indexOf(CLOSE_TAG);
        if (close >= 0) {
          thinking.append(carry, 0, close);
          carry.delete(0, close + CLOSE_TAG.length());
          inThinking = false;
          suppressLeadingWhitespace = true;
        } else {
          int length = carry.length() - (flush ? 0 : matchingSuffix(CLOSE_TAG));
          if (length <= 0) {
            break;
          }
          thinking.append(carry, 0, length);
          carry.delete(0, length);
        }
      } else {
        int open = carry.indexOf(OPEN_TAG);
        if (open >= 0) {
          appendText(text, carry.substring(0, open));
          carry.delete(0, open + OPEN_TAG.length());
          inThinking = true;
        } else {
          int length = carry.length() - (flush ? 0 : matchingSuffix(OPEN_TAG));
          if (length <= 0) {
            break;
          }
          appendText(text, carry.substring(0, length));
          carry.delete(0, length);
        }
      }
    }
    return new Split(text.toString(), thinking.toString());
  }

  private void appendText(StringBuilder target, String value) {
    if (suppressLeadingWhitespace) {
      value = value.stripLeading();
      if (value.isEmpty()) {
        return;
      }
      suppressLeadingWhitespace = false;
    }
    target.append(value);
  }

  private int matchingSuffix(String tag) {
    for (int length = Math.min(carry.length(), tag.length() - 1); length > 0; length--) {
      if (carry.substring(carry.length() - length).equals(tag.substring(0, length))) {
        return length;
      }
    }
    return 0;
  }

  record Split(String text, String thinking) {
    private static final Split EMPTY = new Split("", "");
  }
}
