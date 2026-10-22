package fun.fengwk.kkstudio.common.result;

/** 纯文本结果内容。 */
public record TextResultContent(String text) implements ResultContent {

  public TextResultContent {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
  }
}
