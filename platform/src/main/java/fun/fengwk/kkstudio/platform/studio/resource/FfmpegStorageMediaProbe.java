package fun.fengwk.kkstudio.platform.studio.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageMediaFacts;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 ffprobe 的全局 blob 媒体事实探针。
 *
 * <p>下载 S3 原始对象到临时目录运行 ffprobe，返回权威 mediaType/width/height/durationMs； 探测失败（二进制缺失、内容非媒体、 超时、超限）时回退
 * HEAD 元数据（mediaType + 空尺寸），保证全局上传契约对非媒体内容依旧可用 —— Canvas 资源 DTO 的媒体事实列可空。
 */
@Slf4j
public class FfmpegStorageMediaProbe implements StorageMediaProbe {

  private static final String FALLBACK_MEDIA_TYPE = "application/octet-stream";
  private static final Set<String> JPEG_CODECS = Set.of("mjpeg");
  private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx");
  private static final Set<String> HEIF_BRANDS =
      Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1");
  private static final long MAX_PROBE_SIZE = 100L * 1024 * 1024;

  private final CanvasMediaProperties properties;
  private final SystemSettingsSnapshot snapshot;
  private final S3StorageService storageService;
  private final ObjectMapper objectMapper;
  private final MediaProcessRunner processRunner = new MediaProcessRunner();

  public FfmpegStorageMediaProbe(
      CanvasMediaProperties properties,
      SystemSettingsSnapshot snapshot,
      S3StorageService storageService,
      ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public StorageMediaFacts probe(String s3Key, S3ObjectMetadata headMetadata) {
    Objects.requireNonNull(s3Key, "s3Key");
    Objects.requireNonNull(headMetadata, "headMetadata");
    try (S3ObjectStream object = storageService.readObject(s3Key)) {
      if (object.metadata().contentLength() != headMetadata.contentLength()) {
        throw new IllegalArgumentException("S3 GET metadata size does not match HEAD");
      }
      if (headMetadata.contentLength() <= 0L || headMetadata.contentLength() > MAX_PROBE_SIZE) {
        throw new IllegalArgumentException("probe size is outside the supported range");
      }
      Path workDir = Files.createTempDirectory(properties.getTempDir(), "canvas-probe-");
      try {
        Path original = workDir.resolve("original");
        long actualSize = copyBounded(object.inputStream(), original, MAX_PROBE_SIZE);
        if (actualSize != headMetadata.contentLength()) {
          throw new IllegalArgumentException(
              "probe size mismatch: expected "
                  + headMetadata.contentLength()
                  + " but read "
                  + actualSize);
        }
        return probeFile(original, workDir);
      } catch (RuntimeException error) {
        log.debug("media probe failed key={} type={}", s3Key, error.getClass().getSimpleName());
        return fallback(headMetadata);
      } finally {
        deleteRecursively(workDir);
      }
    } catch (IOException error) {
      throw new UncheckedIOException("failed to read S3 object for media probe", error);
    }
  }

  private StorageMediaFacts probeFile(Path original, Path workDir) {
    Path stdout = workDir.resolve("ffprobe.json");
    Path stderr = workDir.resolve("ffprobe.err");
    processRunner.run(
        List.of(
            properties.getFfprobeBinary(),
            "-v",
            "error",
            "-show_entries",
            "format=format_name,duration:format_tags=major_brand:"
                + "stream=codec_type,codec_name,width,height,duration",
            "-of",
            "json",
            original.toString()),
        stdout,
        stderr,
        Duration.ofMillis(snapshot.get().storageMedia().canvasMediaProcessTimeoutMillis()));
    if (!isNonEmptyFile(stdout)) {
      throw new IllegalArgumentException("ffprobe produced no JSON output");
    }
    FfprobeOutput output;
    try {
      output = objectMapper.readValue(stdout.toFile(), FfprobeOutput.class);
    } catch (IOException error) {
      throw new IllegalArgumentException("ffprobe produced invalid JSON", error);
    }
    if (output.format() == null || output.streams() == null) {
      throw new IllegalArgumentException("ffprobe output is missing format or streams");
    }
    FfprobeStream video =
        output.streams().stream()
            .filter(stream -> "video".equals(stream.codecType()))
            .findFirst()
            .orElse(null);
    if (video != null && isProbeableImage(video, output.format())) {
      return imageFacts(video, output.format());
    }
    if (video != null && isVideoContainer(output.format())) {
      return videoFacts(video, output.format());
    }
    if (video == null && isProbeableAudio(output)) {
      return audioFacts(output);
    }
    throw new IllegalArgumentException("content is not probeable image/video/audio media");
  }

  private StorageMediaFacts imageFacts(FfprobeStream video, FfprobeFormat format) {
    String codec = video.codecName().toLowerCase(Locale.ROOT);
    String mediaType;
    if (JPEG_CODECS.contains(codec)) {
      mediaType = "image/jpeg";
    } else if ("png".equals(codec)) {
      mediaType = "image/png";
    } else if ("webp".equals(codec)) {
      mediaType = "image/webp";
    } else if ("hevc".equals(codec)) {
      String brand = majorBrand(format).toLowerCase(Locale.ROOT);
      mediaType = HEIC_BRANDS.contains(brand) ? "image/heic" : "image/heif";
    } else {
      throw new IllegalArgumentException("unsupported image codec: " + codec);
    }
    return new StorageMediaFacts(mediaType, (long) video.width(), (long) video.height(), null);
  }

  private StorageMediaFacts videoFacts(FfprobeStream video, FfprobeFormat format) {
    String container = "qt".equalsIgnoreCase(majorBrand(format)) ? "mov" : "mp4";
    String mediaType = "mov".equals(container) ? "video/quicktime" : "video/mp4";
    return new StorageMediaFacts(
        mediaType,
        (long) video.width(),
        (long) video.height(),
        durationMs(video.duration(), format.duration(), "video duration"));
  }

  private StorageMediaFacts audioFacts(FfprobeOutput output) {
    String formatName = output.format().formatName();
    String mediaType;
    if (hasFormat(formatName, "wav")) {
      mediaType = "audio/wav";
    } else if (hasFormat(formatName, "mp3")) {
      mediaType = "audio/mpeg";
    } else {
      throw new IllegalArgumentException("unsupported audio container: " + formatName);
    }
    FfprobeStream audio =
        output.streams().stream()
            .filter(stream -> "audio".equals(stream.codecType()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("ffprobe output has no audio stream"));
    return new StorageMediaFacts(
        mediaType,
        null,
        null,
        durationMs(audio.duration(), output.format().duration(), "audio duration"));
  }

  private StorageMediaFacts fallback(S3ObjectMetadata headMetadata) {
    String mediaType = headMetadata.contentType();
    if (mediaType == null || mediaType.isBlank()) {
      mediaType = FALLBACK_MEDIA_TYPE;
    }
    return new StorageMediaFacts(mediaType, null, null, null);
  }

  private static boolean isProbeableImage(FfprobeStream video, FfprobeFormat format) {
    if (video.width() == null
        || video.width() <= 0
        || video.height() == null
        || video.height() <= 0) {
      return false;
    }
    String codec = video.codecName() == null ? "" : video.codecName().toLowerCase(Locale.ROOT);
    if (JPEG_CODECS.contains(codec) && hasFormat(format.formatName(), "jpeg_pipe")) {
      return true;
    }
    if ("png".equals(codec) && hasFormat(format.formatName(), "png_pipe")) {
      return true;
    }
    if ("webp".equals(codec) && hasFormat(format.formatName(), "webp_pipe")) {
      return true;
    }
    return "hevc".equals(codec) && isHeif(format);
  }

  private static boolean isVideoContainer(FfprobeFormat format) {
    return hasFormat(format.formatName(), "mov") || hasFormat(format.formatName(), "mp4");
  }

  private static boolean isProbeableAudio(FfprobeOutput output) {
    if (output.streams().stream().noneMatch(stream -> "audio".equals(stream.codecType()))) {
      return false;
    }
    String formatName = output.format().formatName();
    return hasFormat(formatName, "wav") || hasFormat(formatName, "mp3");
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
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " must be numeric", error);
    }
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
    if (formatNames == null) {
      return false;
    }
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
    } catch (IOException error) {
      return false;
    }
  }

  private static long copyBounded(InputStream content, Path target, long maxSize) {
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
    } catch (IOException error) {
      throw new UncheckedIOException("failed to spool media", error);
    }
  }

  private static void deleteRecursively(Path path) {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              child -> {
                try {
                  Files.deleteIfExists(child);
                } catch (IOException error) {
                  log.warn(
                      "failed to delete temp path {} type={}",
                      child,
                      error.getClass().getSimpleName());
                }
              });
    } catch (IOException error) {
      log.warn("failed to walk temp path {} type={}", path, error.getClass().getSimpleName());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeOutput(List<FfprobeStream> streams, FfprobeFormat format) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeStream(
      @JsonProperty("codec_type") String codecType,
      @JsonProperty("codec_name") String codecName,
      Integer width,
      Integer height,
      String duration) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FfprobeFormat(
      @JsonProperty("format_name") String formatName, String duration, Map<String, String> tags) {}
}
