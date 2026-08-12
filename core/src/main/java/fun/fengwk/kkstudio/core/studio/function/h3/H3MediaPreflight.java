package fun.fengwk.kkstudio.core.studio.function.h3;

import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** MiniMax-H3 对冻结 blob 事实快照的 fail-closed typed preflight。 */
public final class H3MediaPreflight {

  static final long MAX_IMAGE_SIZE = 30L * 1024 * 1024;
  static final long MAX_VIDEO_SIZE = 50L * 1024 * 1024;
  static final long MAX_AUDIO_SIZE = 15L * 1024 * 1024;

  private static final Set<String> IMAGE_MEDIA_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
  private static final Set<String> VIDEO_MEDIA_TYPES = Set.of("video/mp4", "video/quicktime");
  private static final Set<String> AUDIO_MEDIA_TYPES =
      Set.of("audio/wav", "audio/x-wav", "audio/mpeg");

  public H3MediaPreflight() {}

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
    requireSize(reference, MAX_IMAGE_SIZE);
    if (!IMAGE_MEDIA_TYPES.contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 IMAGE mediaType: " + reference.mediaType());
    }
    validateGeometry(reference.width(), reference.height(), "IMAGE");
  }

  private long validateVideo(CanvasFunctionFrozenReference reference) {
    requireSize(reference, MAX_VIDEO_SIZE);
    if (!VIDEO_MEDIA_TYPES.contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 VIDEO mediaType: " + reference.mediaType());
    }
    validateGeometry(reference.width(), reference.height(), "VIDEO");
    long durationMs = validateDuration(reference, "VIDEO");
    return durationMs;
  }

  private long validateAudio(CanvasFunctionFrozenReference reference) {
    requireSize(reference, MAX_AUDIO_SIZE);
    if (!AUDIO_MEDIA_TYPES.contains(reference.mediaType().toLowerCase(Locale.ROOT))) {
      throw invalid("unsupported H3 AUDIO mediaType: " + reference.mediaType());
    }
    return validateDuration(reference, "AUDIO");
  }

  private static void requireSize(CanvasFunctionFrozenReference reference, long max) {
    if (reference.sizeBytes() <= 0L || reference.sizeBytes() > max) {
      throw invalid(reference.kind() + " size is outside the H3 limit");
    }
  }

  private static void validateGeometry(Long width, Long height, String kind) {
    if (width == null || height == null) {
      throw invalid(kind + " media facts must carry width/height");
    }
    if (width < 256 || width > 5760 || height < 256 || height > 5760) {
      throw invalid(kind + " width/height must be between 256 and 5760");
    }
    double ratio = (double) width / height;
    if (!Double.isFinite(ratio) || ratio < 0.4D || ratio > 2.5D) {
      throw invalid(kind + " aspect ratio must be between 0.4 and 2.5");
    }
  }

  private static long validateDuration(CanvasFunctionFrozenReference reference, String kind) {
    Long durationMs = reference.durationMs();
    if (durationMs == null || durationMs < 2_000L || durationMs > 15_000L) {
      throw invalid(kind + " duration must be between 2 and 15 seconds");
    }
    return durationMs;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
