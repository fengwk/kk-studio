package fun.fengwk.kkstudio.harness.tool;

/** 纯文本工具内容。 */
public record TextToolContent(String text) implements ToolContent {

  public TextToolContent {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
  }
}
