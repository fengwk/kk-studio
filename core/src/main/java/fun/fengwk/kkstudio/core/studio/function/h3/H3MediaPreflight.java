package fun.fengwk.kkstudio.core.studio.function.h3;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.core.studio.resource.FfmpegCanvasMediaProcessor;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** MiniMax-H3 对 frozen metadata 的 fail-closed typed preflight。 */
public final class H3MediaPreflight {

  private static final Set<String> IMAGE_CONTAINERS = Set.of("jpeg", "png", "webp", "heic", "heif");
  private static final Set<String> IMAGE_CODECS = Set.of("mjpeg", "png", "webp", "hevc");
  private static final Set<String> VIDEO_CONTAINERS = Set.of("mp4", "mov");
  private static final Set<String> VIDEO_CODECS = Set.of("h264", "hevc");
  private static final Set<String> VIDEO_AUDIO_CODECS = Set.of("aac", "mp3");
  private static final Set<String> AUDIO_CONTAINERS = Set.of("wav", "mp3");

  private final ObjectMapper mapper;

  public H3MediaPreflight(ObjectMapper objectMapper) {
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public void validate(List<CanvasFunctionFrozenReference> manifest) {
    Objects.requireNonNull(manifest, "manifest");
    int images = 0;
    int videos = 0;
    int audios = 0;
    long videoDurationMs = 0L;
    long audioDurationMs = 0L;
    for (CanvasFunctionFrozenReference reference : manifest) {
      switch (reference.kind()) {
        case IMAGE -> {
          images++;
          validateImage(reference);
        }
        case VIDEO -> {
          videos++;
          videoDurationMs = Math.addExact(videoDurationMs, validateVideo(reference));
        }
        case AUDIO -> {
          audios++;
          audioDurationMs = Math.addExact(audioDurationMs, validateAudio(reference));
        }
        case TEXT -> throw invalid("H3 does not accept TEXT references");
      }
    }
    if (images > 9 || videos > 3 || audios > 3 || manifest.size() > 12) {
      throw invalid("H3 reference count exceeds IMAGE=9, VIDEO=3, AUDIO=3 or total=12");
    }
    if (images == 0 && videos == 0) {
      throw invalid("H3 requires at least one IMAGE or VIDEO reference");
    }
    if (videoDurationMs > 15_000L) {
      throw invalid("H3 reference VIDEO duration must total at most 15 seconds");
    }
    if (audioDurationMs > 15_000L) {
      throw invalid("H3 reference AUDIO duration must total at most 15 seconds");
    }
  }

  private void validateImage(CanvasFunctionFrozenReference reference) {
    requireSize(reference, FfmpegCanvasMediaProcessor.MAX_IMAGE_SIZE);
    ObjectNode metadata = metadata(reference);
    String container = text(metadata, "container");
    String codec = text(metadata, "codec");
    int width = integer(metadata, "width");
    int height = integer(metadata, "height");
    if (!IMAGE_CONTAINERS.contains(container) || !IMAGE_CODECS.contains(codec)) {
      throw invalid("unsupported H3 IMAGE container/codec: " + container + "/" + codec);
    }
    if (!validImagePair(container, codec)) {
      throw invalid("mismatched H3 IMAGE container/codec: " + container + "/" + codec);
    }
    if (!Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
        .contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 IMAGE mediaType");
    }
    validateGeometry(width, height, "IMAGE");
  }

  private long validateVideo(CanvasFunctionFrozenReference reference) {
    requireSize(reference, FfmpegCanvasMediaProcessor.MAX_VIDEO_SIZE);
    ObjectNode metadata = metadata(reference);
    String container = text(metadata, "container");
    String videoCodec = text(metadata, "videoCodec");
    JsonNode audioCodecValue = required(metadata, "audioCodec", true);
    String audioCodec =
        audioCodecValue.isNull() ? null : requireTextValue(audioCodecValue, "audioCodec");
    int width = integer(metadata, "width");
    int height = integer(metadata, "height");
    long durationMs = integerLong(metadata, "durationMs");
    double fps = finiteNumber(metadata, "fps");
    if (!VIDEO_CONTAINERS.contains(container) || !VIDEO_CODECS.contains(videoCodec)) {
      throw invalid("unsupported H3 VIDEO container/codec: " + container + "/" + videoCodec);
    }
    if (audioCodec != null && !VIDEO_AUDIO_CODECS.contains(audioCodec)) {
      throw invalid("unsupported H3 VIDEO audio codec: " + audioCodec);
    }
    if (!Set.of("video/mp4", "video/quicktime")
        .contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 VIDEO mediaType");
    }
    validateGeometry(width, height, "VIDEO");
    validateDuration(durationMs, "VIDEO");
    if (fps < 23.976D || fps > 60D) {
      throw invalid("H3 VIDEO fps must be between 23.976 and 60");
    }
    return durationMs;
  }

  private long validateAudio(CanvasFunctionFrozenReference reference) {
    requireSize(reference, FfmpegCanvasMediaProcessor.MAX_AUDIO_SIZE);
    ObjectNode metadata = metadata(reference);
    String container = text(metadata, "container");
    String codec = text(metadata, "codec");
    long durationMs = integerLong(metadata, "durationMs");
    if (!AUDIO_CONTAINERS.contains(container)
        || ("mp3".equals(container) ? !"mp3".equals(codec) : !codec.startsWith("pcm_"))) {
      throw invalid("unsupported H3 AUDIO container/codec: " + container + "/" + codec);
    }
    if (!Set.of("audio/wav", "audio/x-wav", "audio/mpeg")
        .contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 AUDIO mediaType");
    }
    validateDuration(durationMs, "AUDIO");
    return durationMs;
  }

  private ObjectNode metadata(CanvasFunctionFrozenReference reference) {
    try {
      JsonNode value = mapper.readTree(reference.metadataJson());
      if (!(value instanceof ObjectNode object)) {
        throw invalid("frozen metadata must be an object");
      }
      return object;
    } catch (JsonProcessingException error) {
      throw invalid("frozen metadata must be valid typed JSON", error);
    }
  }

  private static void requireSize(CanvasFunctionFrozenReference reference, long max) {
    if (reference.size() <= 0L || reference.size() > max) {
      throw invalid(reference.kind() + " size is outside the H3 limit");
    }
  }

  private static void validateGeometry(int width, int height, String kind) {
    if (width < 256 || width > 5760 || height < 256 || height > 5760) {
      throw invalid(kind + " width/height must be between 256 and 5760");
    }
    double ratio = (double) width / height;
    if (!Double.isFinite(ratio) || ratio < 0.4D || ratio > 2.5D) {
      throw invalid(kind + " aspect ratio must be between 0.4 and 2.5");
    }
  }

  private static void validateDuration(long durationMs, String kind) {
    if (durationMs < 2_000L || durationMs > 15_000L) {
      throw invalid(kind + " duration must be between 2 and 15 seconds");
    }
  }

  private static boolean validImagePair(String container, String codec) {
    return switch (container) {
      case "jpeg" -> "mjpeg".equals(codec);
      case "png" -> "png".equals(codec);
      case "webp" -> "webp".equals(codec);
      case "heic", "heif" -> "hevc".equals(codec);
      default -> false;
    };
  }

  private static String text(ObjectNode node, String field) {
    return requireTextValue(required(node, field, false), field);
  }

  private static String requireTextValue(JsonNode value, String field) {
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw invalid(field + " must be non-blank text");
    }
    return value.textValue().toLowerCase(Locale.ROOT);
  }

  private static int integer(ObjectNode node, String field) {
    JsonNode value = required(node, field, false);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid(field + " must be an integer");
    }
    return value.intValue();
  }

  private static long integerLong(ObjectNode node, String field) {
    JsonNode value = required(node, field, false);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(field + " must be an integer");
    }
    return value.longValue();
  }

  private static double finiteNumber(ObjectNode node, String field) {
    JsonNode value = required(node, field, false);
    if (!value.isNumber()) {
      throw invalid(field + " must be numeric");
    }
    double number = value.doubleValue();
    if (!Double.isFinite(number)) {
      throw invalid(field + " must be finite");
    }
    return number;
  }

  private static JsonNode required(ObjectNode node, String field, boolean nullable) {
    JsonNode value = node.get(field);
    if (value == null || (!nullable && value.isNull())) {
      throw invalid(field + " is required" + (nullable ? "" : " and must not be null"));
    }
    return value;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
