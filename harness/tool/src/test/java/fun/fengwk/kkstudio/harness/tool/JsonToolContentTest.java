package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** JsonToolContent 的原始 JSON 字节上限（树解析前）与严格校验契约测试。 */
class JsonToolContentTest {

  /** 恰好 1 MiB UTF-8 的合法 JSON 接受；超一字节拒绝；未配对代理项拒绝（全部发生在树解析之前）。 */
  @Test
  void boundsRawJsonBeforeParsing() {
    String atLimit = "\"" + "a".repeat(JsonToolContent.MAX_JSON_UTF8_BYTES - 2) + "\"";
    assertEquals(atLimit, new JsonToolContent(atLimit).json());

    String overLimit = "\"" + "a".repeat(JsonToolContent.MAX_JSON_UTF8_BYTES - 1) + "\"";
    assertThrows(IllegalArgumentException.class, () -> new JsonToolContent(overLimit));

    // 多字节字符：按 UTF-8 字节计数（3 字节/字符），而非字符数。
    int cjkChars = (JsonToolContent.MAX_JSON_UTF8_BYTES - 2) / 3;
    String cjkAtLimit = "\"" + "中".repeat(cjkChars) + "\"";
    assertEquals(cjkAtLimit, new JsonToolContent(cjkAtLimit).json());
    String cjkOver = "\"" + "中".repeat(cjkChars + 1) + "\"";
    assertThrows(IllegalArgumentException.class, () -> new JsonToolContent(cjkOver));

    // 未配对代理项与非法 JSON 仍严格拒绝。
    assertThrows(IllegalArgumentException.class, () -> new JsonToolContent("\"\uD800\""));
    assertThrows(IllegalArgumentException.class, () -> new JsonToolContent("not json"));
  }
}
