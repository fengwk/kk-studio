package fun.fengwk.kkstudio.studio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CanvasResourcePathsTest {

  @Test
  void generatesOnlyTheDeterministicOriginalAndPreviewKeys() {
    assertEquals("canvases/12/resources/34/original", CanvasResourcePaths.original(12L, 34L));
    assertEquals("canvases/12/resources/34/preview.webp", CanvasResourcePaths.preview(12L, 34L));
    assertThrows(IllegalArgumentException.class, () -> CanvasResourcePaths.original(0L, 34L));
    assertThrows(IllegalArgumentException.class, () -> CanvasResourcePaths.preview(12L, -1L));
  }
}
