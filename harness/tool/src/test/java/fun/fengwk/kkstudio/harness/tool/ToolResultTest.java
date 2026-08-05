package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** ToolResult.detailsJson 的原始 JSON 字节上限（树解析前）与规范化契约测试。 */
class ToolResultTest {

  /** 恰好 1 MiB UTF-8 的合法 details 对象接受；超一字节拒绝；未配对代理项拒绝。 */
  @Test
  void boundsDetailsJsonBeforeParsing() {
    String atLimit = "{\"d\":\"" + "a".repeat(ToolResult.MAX_DETAILS_JSON_UTF8_BYTES - 8) + "\"}";
    assertEquals(atLimit, new ToolResult("c", List.of(), false, atLimit, false).detailsJson());

    String overLimit = "{\"d\":\"" + "a".repeat(ToolResult.MAX_DETAILS_JSON_UTF8_BYTES - 7) + "\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResult("c", List.of(), false, overLimit, false));

    // 未配对代理项：在树解析前拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResult("c", List.of(), false, "{\"d\":\"\uD800\"}", false));
  }

  /** toolCallId 必须非空；error 工厂构造标准错误结果。 */
  @Test
  void validatesToolCallIdAndBuildsErrorResults() {
    assertThrows(
        IllegalArgumentException.class, () -> new ToolResult(" ", List.of(), false, "{}", false));
    assertThrows(
        IllegalArgumentException.class, () -> new ToolResult(null, List.of(), false, "{}", false));

    ToolResult error = ToolResult.error("call-1", "boom");
    assertEquals("call-1", error.toolCallId());
    assertTrue(error.error());
    assertEquals("boom", ((TextToolContent) error.contents().getFirst()).text());
    assertEquals("{}", error.detailsJson());
  }

  /** contents 元素数上限：恰好 64 接受；65 在 {@link List#copyOf} 之前拒绝；null list / null 元素拒绝。 */
  @Test
  void boundsContentsItemCountBeforeCopy() {
    List<ToolContent> atLimit = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      atLimit.add(new TextToolContent("x"));
    }
    assertEquals(
        ToolResult.MAX_CONTENT_ITEMS,
        new ToolResult("c", atLimit, false, "{}", false).contents().size());

    List<ToolContent> overLimit = new ArrayList<>(atLimit);
    overLimit.add(new TextToolContent("x"));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ToolResult("c", overLimit, false, "{}", false));
    assertTrue(error.getMessage().contains(String.valueOf(ToolResult.MAX_CONTENT_ITEMS)));

    assertThrows(NullPointerException.class, () -> new ToolResult("c", null, false, "{}", false));

    List<ToolContent> withNullElement = new ArrayList<>();
    withNullElement.add(null);
    assertThrows(
        NullPointerException.class, () -> new ToolResult("c", withNullElement, false, "{}", false));
  }
}
