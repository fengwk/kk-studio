package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CanvasResourceKindTest {

  /** 规范 MIME 媒体类型前缀正确映射到对应资源种类。 */
  @Test
  void mapsStandardMediaTypesToKind() {
    assertEquals(CanvasResourceKind.IMAGE, CanvasResourceKind.fromMediaType("image/png"));
    assertEquals(CanvasResourceKind.IMAGE, CanvasResourceKind.fromMediaType("image/jpeg"));
    assertEquals(CanvasResourceKind.VIDEO, CanvasResourceKind.fromMediaType("video/mp4"));
    assertEquals(CanvasResourceKind.AUDIO, CanvasResourceKind.fromMediaType("audio/wav"));
    assertEquals(CanvasResourceKind.TEXT, CanvasResourceKind.fromMediaType("text/plain"));
  }

  /** 空或不支持的媒体类型明确拒绝。 */
  @Test
  void rejectsNullOrUnsupportedMediaType() {
    IllegalArgumentException nullError =
        assertThrows(IllegalArgumentException.class, () -> CanvasResourceKind.fromMediaType(null));
    assertEquals("blob mediaType must not be null", nullError.getMessage());

    IllegalArgumentException unsupportedError =
        assertThrows(
            IllegalArgumentException.class,
            () -> CanvasResourceKind.fromMediaType("application/json"));
    assertEquals("unsupported blob mediaType: application/json", unsupportedError.getMessage());
  }
}
