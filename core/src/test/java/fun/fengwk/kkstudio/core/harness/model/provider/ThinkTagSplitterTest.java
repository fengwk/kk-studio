package fun.fengwk.kkstudio.core.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 正文 think 标签必须拆到 thinking，正文侧不残留标签。 */
class ThinkTagSplitterTest {

  @Test
  void parseSplitsTaggedReasoningFromFinalAnswer() {
    ThinkTagSplitter.Split split =
        ThinkTagSplitter.parse("<think>plan the greeting</think>\n\nHello there!");
    assertEquals("Hello there!", split.text());
    assertEquals("plan the greeting", split.thinking());
  }

  @Test
  void streamingChunksDoNotLeakPartialTags() {
    ThinkTagSplitter splitter = new ThinkTagSplitter();
    ThinkTagSplitter.Split first = splitter.append("<thi");
    ThinkTagSplitter.Split second = splitter.append("nk>hidden</thi");
    ThinkTagSplitter.Split third = splitter.append("nk>\nVisible");
    ThinkTagSplitter.Split done = splitter.finish();

    assertEquals("", first.text());
    assertEquals("", first.thinking());
    assertEquals("", second.text());
    assertEquals("hidden", second.thinking());
    assertEquals("Visible", third.text());
    assertEquals("", third.thinking());
    assertEquals("", done.text());
    assertEquals("", done.thinking());
  }
}
