package fun.fengwk.kkstudio.core.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;

import java.util.ArrayList;
import java.util.List;

/** H3 preflight 覆盖所有素材类型的数量、格式、typed metadata、几何、时长与总时长边界。 */
class H3MediaPreflightTest {

  private final H3MediaPreflight preflight = new H3MediaPreflight(new ObjectMapper());

  @Test
  void acceptsValidMixedManifestAndUnknownMetadataFields() {
    assertDoesNotThrow(
        () ->
            preflight.validate(
                List.of(
                    image(1L, 256, 640, ",\"future\":true"),
                    video(2L, 2_000L, 23.976D, "aac"),
                    audio(3L, 15_000L))));
  }

  @Test
  void rejectsAudioOnlyCountsAndDurationTotals() {
    assertThrows(
        IllegalArgumentException.class, () -> preflight.validate(List.of(audio(1L, 2_000L))));

    List<CanvasFunctionFrozenReference> tooMany = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tooMany.add(image(i + 1L, 512, 512, ""));
    }
    assertThrows(IllegalArgumentException.class, () -> preflight.validate(tooMany));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, 512, 512, ""),
                    video(2L, 8_000L, 24D, null),
                    video(3L, 8_000L, 24D, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(image(1L, 512, 512, ""), audio(2L, 8_000L), audio(3L, 8_000L))));
  }

  @Test
  void rejectsMissingWrongOrOutOfRangeMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    reference(
                        1L,
                        CanvasResourceKind.IMAGE,
                        "image/png",
                        "{\"container\":\"png\",\"codec\":\"png\",\"width\":512}"))));
    assertThrows(
        IllegalArgumentException.class, () -> preflight.validate(List.of(image(1L, 255, 512, ""))));
    assertThrows(
        IllegalArgumentException.class, () -> preflight.validate(List.of(image(1L, 256, 641, ""))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, 512, 512, ""),
                    reference(
                        2L,
                        CanvasResourceKind.VIDEO,
                        "video/mp4",
                        "{\"container\":\"mp4\",\"videoCodec\":\"vp9\",\"audioCodec\":null,"
                            + "\"width\":512,\"height\":512,\"durationMs\":2000,\"fps\":24}"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> preflight.validate(List.of(image(1L, 512, 512, ""), video(2L, 1_999L, 24D, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(List.of(image(1L, 512, 512, ""), video(2L, 2_000L, 60.001D, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            preflight.validate(
                List.of(
                    image(1L, 512, 512, ""),
                    reference(
                        2L,
                        CanvasResourceKind.AUDIO,
                        "audio/wav",
                        "{\"container\":\"wav\",\"codec\":\"aac\",\"durationMs\":2000}"))));
  }

  private static CanvasFunctionFrozenReference image(long id, int width, int height, String extra) {
    return reference(
        id,
        CanvasResourceKind.IMAGE,
        "image/png",
        "{\"container\":\"png\",\"codec\":\"png\",\"width\":"
            + width
            + ",\"height\":"
            + height
            + extra
            + "}");
  }

  private static CanvasFunctionFrozenReference video(
      long id, long durationMs, double fps, String audioCodec) {
    return reference(
        id,
        CanvasResourceKind.VIDEO,
        "video/mp4",
        "{\"container\":\"mp4\",\"videoCodec\":\"h264\",\"audioCodec\":"
            + (audioCodec == null ? "null" : "\"" + audioCodec + "\"")
            + ",\"width\":512,\"height\":512,\"durationMs\":"
            + durationMs
            + ",\"fps\":"
            + fps
            + "}");
  }

  private static CanvasFunctionFrozenReference audio(long id, long durationMs) {
    return reference(
        id,
        CanvasResourceKind.AUDIO,
        "audio/mpeg",
        "{\"container\":\"mp3\",\"codec\":\"mp3\",\"durationMs\":" + durationMs + "}");
  }

  private static CanvasFunctionFrozenReference reference(
      long id, CanvasResourceKind kind, String mediaType, String metadata) {
    return new CanvasFunctionFrozenReference(
        id, 0, id, kind, id + ".bin", mediaType, 1024L, metadata);
  }
}
