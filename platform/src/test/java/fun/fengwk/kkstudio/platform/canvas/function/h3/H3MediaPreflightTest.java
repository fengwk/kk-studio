package fun.fengwk.kkstudio.platform.canvas.function.h3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** H3 preflight 覆盖所有素材类型的数量、格式、blob 事实、几何、时长与总时长边界。 */
class H3MediaPreflightTest {

  private static final long IMAGE_MAX = 30L * 1024 * 1024;
  private static final long VIDEO_MAX = 50L * 1024 * 1024;
  private static final long AUDIO_MAX = 15L * 1024 * 1024;

  private final H3MediaPreflight preflight = new H3MediaPreflight();

  @Test
  void acceptsValidMixedManifest() {
    assertDoesNotThrow(
        () ->
            preflight.validate(
                List.of(
                    image(1L, "image/png", 256, 640),
                    video(2L, "video/mp4", 2_000L),
                    audio(3L, "audio/mpeg", 15_000L))));
  }

  @Test
  void rejectsAudioOnlyCountsAndDurationTotals() {
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(audio(1L, "audio/mpeg", 2_000L))));

    List<CanvasFunctionFrozenReference> tooMany = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tooMany.add(image(i + 1L, "image/png", 512, 512));
    }
    assertThrows(IllegalArgumentException.class, () -> preflight.validate(tooMany));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, "image/png", 512, 512),
                    video(2L, "video/mp4", 8_000L),
                    video(3L, "video/mp4", 8_000L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, "image/png", 512, 512),
                    audio(2L, "audio/mpeg", 8_000L),
                    audio(3L, "audio/mpeg", 8_000L))));
  }

  @Test
  void rejectsMissingWrongOrOutOfRangeFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    reference(
                        1L, CanvasResourceKind.IMAGE, "image/png", 1024L, null, null, null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(image(1L, "image/png", 255, 512))));
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(image(1L, "image/png", 256, 641))));
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(image(1L, "image/gif", 512, 512))));
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(image(1L, "image/png", 512, 512, IMAGE_MAX + 1L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(image(1L, "image/png", 512, 512), video(2L, "video/webm", 2_000L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(image(1L, "image/png", 512, 512), video(2L, "video/mp4", 1_999L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, "image/png", 512, 512),
                    video(2L, "video/mp4", 2_000L, VIDEO_MAX + 1L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(image(1L, "image/png", 512, 512), audio(2L, "audio/ogg", 2_000L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, "image/png", 512, 512),
                    audio(2L, "audio/mpeg", 2_000L, AUDIO_MAX + 1L))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    reference(
                        1L, CanvasResourceKind.TEXT, "text/plain", 1024L, null, null, null))));
  }

  private static CanvasFunctionFrozenReference image(
      long id, String mediaType, int width, int height) {
    return image(id, mediaType, width, height, 1024L);
  }

  private static CanvasFunctionFrozenReference image(
      long id, String mediaType, int width, int height, long size) {
    return reference(
        id, CanvasResourceKind.IMAGE, mediaType, size, (long) width, (long) height, null);
  }

  private static CanvasFunctionFrozenReference video(long id, String mediaType, long durationMs) {
    return video(id, mediaType, durationMs, 1024L);
  }

  private static CanvasFunctionFrozenReference video(
      long id, String mediaType, long durationMs, long size) {
    return reference(id, CanvasResourceKind.VIDEO, mediaType, size, 512L, 512L, durationMs);
  }

  private static CanvasFunctionFrozenReference audio(long id, String mediaType, long durationMs) {
    return audio(id, mediaType, durationMs, 1024L);
  }

  private static CanvasFunctionFrozenReference audio(
      long id, String mediaType, long durationMs, long size) {
    return reference(id, CanvasResourceKind.AUDIO, mediaType, size, null, null, durationMs);
  }

  private static CanvasFunctionFrozenReference reference(
      long id,
      CanvasResourceKind kind,
      String mediaType,
      long size,
      Long width,
      Long height,
      Long durationMs) {
    UUID resource = new UUID(0L, id);
    return new CanvasFunctionFrozenReference(
        resource,
        0,
        resource,
        resource,
        kind,
        id + ".bin",
        mediaType,
        size,
        width,
        height,
        durationMs);
  }
}
