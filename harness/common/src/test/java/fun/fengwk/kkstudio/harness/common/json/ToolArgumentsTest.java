package fun.fengwk.kkstudio.harness.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** {@link ToolArguments} 的容错边界测试：历史 action 渲染器只描述过去发生的事，因此任何畸形输入都必须退化为“未提供”而不是抛出。 */
class ToolArgumentsTest {

  /** null / 空白 / 非法 JSON / 非对象都解析为 null，调用方据此回退通用描述。 */
  @Test
  void parseRejectsAnythingThatIsNotAJsonObject() {
    assertNull(ToolArguments.parse(null));
    assertNull(ToolArguments.parse(""));
    assertNull(ToolArguments.parse("   "));
    assertNull(ToolArguments.parse("{"));
    assertNull(ToolArguments.parse("[1,2]"));
    assertNull(ToolArguments.parse("\"text\""));
    assertNull(ToolArguments.parse("null"));
  }

  /** 合法 JSON 对象按 tree 暴露，字段读取不改变原文本。 */
  @Test
  void parseAcceptsJsonObject() {
    JsonNode arguments = ToolArguments.parse("{\"path\":\"/tmp/a\",\"replace_all\":true}");
    assertEquals("/tmp/a", ToolArguments.text(arguments, "path"));
    assertTrue(ToolArguments.flag(arguments, "replace_all"));
  }

  /** 文本字段逐字保留；缺失、null、非文本与纯空白都视为未提供。 */
  @Test
  void textPreservesNonBlankTextVerbatim() {
    JsonNode arguments =
        ToolArguments.parse("{\"name\":\"  goal  \",\"blank\":\"   \",\"count\":3,\"nil\":null}");
    assertEquals("  goal  ", ToolArguments.text(arguments, "name"));
    assertNull(ToolArguments.text(arguments, "blank"));
    assertNull(ToolArguments.text(arguments, "count"));
    assertNull(ToolArguments.text(arguments, "nil"));
    assertNull(ToolArguments.text(arguments, "missing"));
    assertNull(ToolArguments.text(null, "name"));
  }

  /** 历史语义不能接受宽松 JSON：重复字段或尾随 token 必须触发安全回退。 */
  @Test
  void parseRejectsDuplicateFieldsAndTrailingTokens() {
    assertNull(ToolArguments.parse("{\"path\":\"a\",\"path\":\"b\"}"));
    assertNull(ToolArguments.parse("{\"path\":\"a\"} {}"));
  }

  /** 布尔字段只认真正的 JSON boolean；缺失、非布尔与 null 都是 false。 */
  @Test
  void flagReadsBooleansOnly() {
    JsonNode arguments =
        ToolArguments.parse("{\"yes\":true,\"no\":false,\"text\":\"true\",\"nil\":null}");
    assertTrue(ToolArguments.flag(arguments, "yes"));
    assertFalse(ToolArguments.flag(arguments, "no"));
    assertFalse(ToolArguments.flag(arguments, "text"));
    assertFalse(ToolArguments.flag(arguments, "nil"));
    assertFalse(ToolArguments.flag(arguments, "missing"));
    assertFalse(ToolArguments.flag(null, "yes"));
  }

  /** render 把解析与字段读取一起延后：builder 必须同时处理解析失败（null）与成功两种情况。 */
  @Test
  void renderDelegatesParsedArgumentsToBuilder() {
    assertEquals(
        "read /tmp/a",
        ToolArguments.render(
            "{\"path\":\"/tmp/a\"}", arguments -> "read " + ToolArguments.text(arguments, "path")));
    assertEquals(
        "read something",
        ToolArguments.render("{", arguments -> "read " + (arguments == null ? "something" : "?")));
  }
}
