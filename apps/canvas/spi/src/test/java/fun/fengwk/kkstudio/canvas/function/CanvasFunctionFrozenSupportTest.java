package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CanvasFunctionFrozenSupportTest {

  /** 非空白文本通过校验。 */
  @Test
  void acceptsNonBlankText() {
    assertDoesNotThrow(() -> CanvasFunctionFrozenSupport.requireText("valid", "field"));
    assertDoesNotThrow(() -> CanvasFunctionFrozenSupport.requireText(" padded ", "field"));
  }

  /** null 或空白文本抛出带字段名的 IllegalArgumentException。 */
  @Test
  void rejectsNullOrBlankText() {
    IllegalArgumentException nullError =
        assertThrows(
            IllegalArgumentException.class,
            () -> CanvasFunctionFrozenSupport.requireText(null, "sampleField"));
    assertEquals("sampleField must not be blank", nullError.getMessage());

    IllegalArgumentException blankError =
        assertThrows(
            IllegalArgumentException.class,
            () -> CanvasFunctionFrozenSupport.requireText("   ", "sampleField"));
    assertEquals("sampleField must not be blank", blankError.getMessage());
  }
}
