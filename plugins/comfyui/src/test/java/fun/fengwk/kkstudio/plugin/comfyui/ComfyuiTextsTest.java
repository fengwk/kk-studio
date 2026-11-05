package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ComfyuiTexts} 文本校验助手单元测试。 */
class ComfyuiTextsTest {

  @Test
  void returnsOriginalValueWhenNonBlank() {
    // 验证合法非空文本保持原样返回（包含前导后导空格不被静默裁剪）。
    assertEquals("hello", ComfyuiTexts.requireText("hello", "demoField"));
    assertEquals("  spaced  ", ComfyuiTexts.requireText("  spaced  ", "demoField"));
  }

  @Test
  void throwsIllegalArgumentExceptionWhenNullOrBlank() {
    // 验证 null、空串及纯空白串均按固定文案抛出 IllegalArgumentException。
    IllegalArgumentException exNull =
        assertThrows(
            IllegalArgumentException.class, () -> ComfyuiTexts.requireText(null, "demoField"));
    assertEquals("demoField must not be blank", exNull.getMessage());

    IllegalArgumentException exEmpty =
        assertThrows(
            IllegalArgumentException.class, () -> ComfyuiTexts.requireText("", "demoField"));
    assertEquals("demoField must not be blank", exEmpty.getMessage());

    IllegalArgumentException exBlank =
        assertThrows(
            IllegalArgumentException.class,
            () -> ComfyuiTexts.requireText("   \t\n  ", "demoField"));
    assertEquals("demoField must not be blank", exBlank.getMessage());
  }
}
