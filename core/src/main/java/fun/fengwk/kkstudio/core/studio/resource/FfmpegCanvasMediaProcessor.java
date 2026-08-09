package fun.fengwk.kkstudio.core.studio.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 基于 ffprobe/ffmpeg 的 Canvas 媒体校验与 WebP preview 实现。 */
public class FfmpegCanvasMediaProcessor implements CanvasMediaProcessor {

  public static final long MAX_IMAGE_SIZE = 30L * 1024 * 1024;
  public static final long MAX_VIDEO_SIZE = 50L * 1024 * 1024;
  public static final long MAX_AUDIO_SIZE = 15L * 1024 * 1024;

  private static final Set<String> JPEG_CODECS = Set.of("mjpeg");
  private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx");
  private static final Set<String> HEIF_BRANDS =
      Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1");

  private final CanvasMediaProperties properties;
  private final ObjectMapper objectMapper;
  private final MediaProcessRunner processRunner;

  public FfmpegCanvasMediaProcessor(CanvasMediaProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    validateProperties(properties);
    this.processRunner = new MediaProcessRunner(properties.getProcessTimeout());
  }

  @Override
  public CanvasProcessedMedia process(
      CanvasResourceKind kind, InputStream content, long expectedSize) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(content, "content");
    if (kind == CanvasResourceKind.TEXT) {
      throw new IllegalArgumentException("TEXT cannot be processed as uploaded media");
    }
    long maxSize = maxSize(kind);
    if (expectedSize <= 0L || expectedSize > maxSize) {
      throw new IllegalArgumentException(
          kind + " size must be between 1 and " + maxSize + " bytes");
    }

    Path workDir = createWorkDir();
    try {
      Path original = workDir.resolve("original");
      long actualSize = copyBounded(content, original, maxSize);
      if (actualSize != expectedSize) {
        throw new IllegalArgumentException(
            "media size mismatch: expected " + expectedSize + " but read " + actualSize);
      }
      ProbeResult probe = probe(kind, original, workDir);
      Path preview =
          kind == CanvasResourceKind.AUDIO ? null : createPreview(kind, original, workDir);
      return new CanvasProcessedMedia(
          workDir, original, preview, actualSize, probe.mediaType(), probe.metadataJson());
    } catch (RuntimeException e) {
      CanvasProcessedMedia.deleteRecursively(workDir);
      throw e;
    }
  }

  private ProbeResult probe(CanvasResourceKind kind, Path original, Path workDir) {
    Path stdout = workDir.resolve("ffprobe.json");
    Path stderr = workDir.resolve("ffprobe.err");
    processRunner.run(
        List.of(
            properties.getFfprobeBinary(),
            "-v",
            "error",
            "-show_entries",
            "format=format_name,duration:format_tags=major_brand:"
                + "stream=codec_type,codec_name,width,height,duration,avg_frame_rate,r_frame_rate",
            "-of",
            "json",
            original.toString()),
        stdout,
        stderr);
    if (!isNonEmptyFile(stdout)) {
      throw new IllegalArgumentException("ffprobe produced no JSON output");
    }

    FfprobeOutput output;
    try {
      output = objectMapper.readValue(stdout.toFile(), FfprobeOutput.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("ffprobe produced invalid JSON", e);
    }
    if (output.format() == null || output.streams() == null) {
      throw new IllegalArgumentException("ffprobe output is missing format or streams");
    }
    return switch (kind) {
      case IMAGE -> imageProbe(output);
      case VIDEO -> videoProbe(output);
      case AUDIO -> audioProbe(output);
      case TEXT -> throw new IllegalArgumentException("TEXT cannot be probed as media");
    };
  }

  private ProbeResult imageProbe(FfprobeOutput output) {
    FfprobeStream video = requireStream(output, "video");
    String codec = requireText(video.codecName(), "image codec");
    int width = requirePositive(video.width(), "image width");
    int height = requirePositive(video.height(), "image height");
    String formatName = requireText(output.format().formatName(), "image container");
    String mediaType;
    String container;
    if (JPEG_CODECS.contains(codec)) {
      mediaType = "image/jpeg";
      container = "jpeg";
    } else if ("png".equals(codec)) {
      mediaType = "image/png";
      container = "png";
    } else if ("webp".equals(codec)) {
      mediaType = "image/webp";
      container = "webp";
    } else if ("hevc".equals(codec) && isHeif(output.format())) {
      String brand = majorBrand(output.format());
      mediaType =
          HEIC_BRANDS.contains(brand.toLowerCase(Locale.ROOT)) ? "image/heic" : "image/heif";
      container = mediaType.substring("image/".length());
    } else {
      throw new IllegalArgumentException(
          "unsupported IMAGE format: container=" + formatName + ", codec=" + codec);
    }
    return new ProbeResult(mediaType, toJson(new ImageMetadata(container, codec, width, height)));
  }

  private ProbeResult videoProbe(FfprobeOutput output) {
    String formatName = requireText(output.format().formatName(), "video container");
    if (!hasFormat(formatName, "mov") && !hasFormat(formatName, "mp4")) {
      throw new IllegalArgumentException("unsupported VIDEO container: " + formatName);
    }
    FfprobeStream video = requireStream(output, "video");
    String videoCodec = requireText(video.codecName(), "video codec");
    int width = requirePositive(video.width(), "video width");
    int height = requirePositive(video.height(), "video height");
    long durationMs = durationMs(video.duration(), output.format().duration(), "video duration");
    double fps = parseFrameRate(video.avgFrameRate(), video.rFrameRate());
    String audioCodec =
        output.streams().stream()
            .filter(stream -> "audio".equals(stream.codecType()))
            .map(FfprobeStream::codecName)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    String container = "qt".equalsIgnoreCase(majorBrand(output.format())) ? "mov" : "mp4";
    String mediaType = "mov".equals(container) ? "video/quicktime" : "video/mp4";
    return new ProbeResult(
        mediaType,
        toJson(
            new VideoMetadata(container, videoCodec, audioCodec, width, height, durationMs, fps)));
  }

  private ProbeResult audioProbe(FfprobeOutput output) {
    String formatName = requireText(output.format().formatName(), "audio container");
    String container;
    String mediaType;
    if (hasFormat(formatName, "wav")) {
      container = "wav";
      mediaType = "audio/wav";
    } else if (hasFormat(formatName, "mp3")) {
      container = "mp3";
      mediaType = "audio/mpeg";
    } else {
      throw new IllegalArgumentException("unsupported AUDIO container: " + formatName);
    }
    FfprobeStream audio = requireStream(output, "audio");
    String codec = requireText(audio.codecName(), "audio codec");
    long durationMs = durationMs(audio.duration(), output.format().duration(), "audio duration");
    return new ProbeResult(mediaType, toJson(new AudioMetadata(container, codec, durationMs)));
  }

  private Path createPreview(CanvasResourceKind kind, Path original, Path workDir) {
    Path preview = workDir.resolve("preview.webp");
    Path stdout = workDir.resolve("ffmpeg.out");
    Path stderr = workDir.resolve("ffmpeg.err");
    List<String> command = new ArrayList<>();
    command.add(properties.getFfmpegBinary());
    command.addAll(List.of("-v", "error", "-nostdin", "-y", "-i", original.toString()));
    command.addAll(
        List.of(
            "-vf",
            "scale="
                + properties.getThumbnailMaxDimension()
                + ":"
                + properties.getThumbnailMaxDimension()
                + ":force_original_aspect_ratio=decrease",
            "-frames:v",
            "1",
            "-c:v",
            "libwebp",
            "-quality",
            Integer.toString(properties.getThumbnailQuality())));
    if (kind == CanvasResourceKind.VIDEO) {
      command.addAll(List.of("-an"));
    }
    command.add(preview.toString());
    processRunner.run(command, stdout, stderr);
    if (!isNonEmptyFile(preview)) {
      throw new IllegalArgumentException("ffmpeg did not create preview.webp");
    }
    return preview;
  }

  private Path createWorkDir() {
    try {
      Files.createDirectories(properties.getTempDir());
      return Files.createTempDirectory(properties.getTempDir(), "canvas-resource-");
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create Canvas media temp directory", e);
    }
  }

  private long copyBounded(InputStream content, Path target, long maxSize) {
    try (OutputStream output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[8192];
      long total = 0L;
      int read;
      while ((read = content.read(buffer)) != -1) {
        total += read;
        if (total > maxSize) {
          throw new IllegalArgumentException("media exceeds maximum size");
        }
        output.write(buffer, 0, read);
      }
      return total;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to spool Canvas media", e);
    }
  }

  private String toJson(Object metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize typed Canvas media metadata", e);
    }
  }

  private static FfprobeStream requireStream(FfprobeOutput output, String codecType) {
    return output.streams().stream()
        .filter(stream -> codecType.equals(stream.codecType()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("ffprobe output has no " + codecType + " stream"));
  }

  private static long durationMs(String streamDuration, String formatDuration, String field) {
    double seconds = parsePositiveDouble(firstText(streamDuration, formatDuration), field);
    double millis = seconds * 1000D;
    if (!Double.isFinite(millis) || millis > Long.MAX_VALUE) {
      throw new IllegalArgumentException(field + " is outside supported range");
    }
    long rounded = Math.round(millis);
    if (rounded <= 0L) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return rounded;
  }

  private static double parseFrameRate(String average, String fallback) {
    String value = firstText(average, fallback);
    if (value == null) {
      throw new IllegalArgumentException("video fps is missing");
    }
    String[] parts = value.split("/", -1);
    double fps;
    try {
      if (parts.length == 2) {
        double numerator = Double.parseDouble(parts[0]);
        double denominator = Double.parseDouble(parts[1]);
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator == 0D) {
          throw new IllegalArgumentException("video fps must be finite and positive");
        }
        fps = numerator / denominator;
      } else {
        fps = Double.parseDouble(value);
      }
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("video fps must be numeric", e);
    }
    if (!Double.isFinite(fps) || fps <= 0D) {
      throw new IllegalArgumentException("video fps must be finite and positive");
    }
    return fps;
  }

  private static double parsePositiveDouble(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is missing");
    }
    try {
      double parsed = Double.parseDouble(value);
      if (!Double.isFinite(parsed) || parsed <= 0D) {
        throw new IllegalArgumentException(field + " must be finite and positive");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " must be numeric", e);
    }
  }

  private static int requirePositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is missing");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String firstText(String first, String second) {
    if (first != null && !first.isBlank() && !"N/A".equalsIgnoreCase(first)) {
      return first;
    }
    if (second != null && !second.isBlank() && !"N/A".equalsIgnoreCase(second)) {
      return second;
    }
    return null;
  }

  private static boolean hasFormat(String formatNames, String expected) {
    return Arrays.stream(formatNames.split(","))
        .map(String::strip)
        .anyMatch(expected::equalsIgnoreCase);
  }

  private static boolean isHeif(FfprobeFormat format) {
    return HEIF_BRANDS.contains(majorBrand(format).toLowerCase(Locale.ROOT));
  }

  private static String majorBrand(FfprobeFormat format) {
    if (format.tags() == null) {
      return "";
    }
    return format.tags().getOrDefault("major_brand", "").strip();
  }

  private static boolean isNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0L;
    } catch (IOException e) {
      return false;
    }
  }

  private static long maxSize(CanvasResourceKind kind) {
    return switch (kind) {
      case IMAGE -> MAX_IMAGE_SIZE;
      case VIDEO -> MAX_VIDEO_SIZE;
      case AUDIO -> MAX_AUDIO_SIZE;
      case TEXT -> throw new IllegalArgumentException("TEXT cannot be uploaded");
    };
  }

  private static void validateProperties(CanvasMediaProperties properties) {
    if (properties.getFfprobeBinary() == null || properties.getFfprobeBinary().isBlank()) {
      throw new IllegalArgumentException("ffprobeBinary must not be blank");
    }
    if (properties.getFfmpegBinary() == null || properties.getFfmpegBinary().isBlank()) {
      throw new IllegalArgumentException("ffmpegBinary must not be blank");
    }
    if (properties.getThumbnailMaxDimension() <= 0) {
      throw new IllegalArgumentException("thumbnailMaxDimension must be positive");
    }
    if (properties.getThumbnailQuality() < 1 || properties.getThumbnailQuality() > 100) {
      throw new IllegalArgumentException("thumbnailQuality must be between 1 and 100");
    }
    Objects.requireNonNull(properties.getTempDir(), "tempDir");
  }

  private record ProbeResult(String mediaType, String metadataJson) {}

  private record ImageMetadata(String container, String codec, int width, int height) {}

  private record VideoMetadata(
      String container,
      String videoCodec,
      String audioCodec,
      int width,
      int height,
      long durationMs,
      double fps) {}

  private record AudioMetadata(String container, String codec, long durationMs) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeOutput(List<FfprobeStream> streams, FfprobeFormat format) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeStream(
      @JsonProperty("codec_type") String codecType,
      @JsonProperty("codec_name") String codecName,
      Integer width,
      Integer height,
      String duration,
      @JsonProperty("avg_frame_rate") String avgFrameRate,
      @JsonProperty("r_frame_rate") String rFrameRate) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeFormat(
      @JsonProperty("format_name") String formatName, String duration, Map<String, String> tags) {}
}
