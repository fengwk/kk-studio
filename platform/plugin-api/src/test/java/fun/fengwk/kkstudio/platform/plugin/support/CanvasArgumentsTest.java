package fun.fengwk.kkstudio.platform.plugin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;

class CanvasArgumentsTest {
  @Test
  void textPreservesMissingNullAndTypeSemantics() {
    // 仅缺失参数可使用默认值；显式 null 和其它类型仍拒绝。
    JsonObject args = (JsonObject) CanvasJson.parse("{\"text\":\"\",\"nil\":null,\"number\":1}");
    assertEquals("", CanvasArguments.extractText(args, "text"));
    assertEquals("", CanvasArguments.extractText(args, "text", "fallback"));
    assertEquals("fallback", CanvasArguments.extractText(args, "missing", "fallback"));
    for (String field : new String[] {"missing", "nil", "number"}) {
      assertEquals(
          field + " must be a string",
          assertThrows(
                  IllegalArgumentException.class, () -> CanvasArguments.extractText(args, field))
              .getMessage());
    }
    for (String field : new String[] {"nil", "number"}) {
      assertEquals(
          field + " must be a string",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> CanvasArguments.extractText(args, field, "fallback"))
              .getMessage());
    }
  }
}
