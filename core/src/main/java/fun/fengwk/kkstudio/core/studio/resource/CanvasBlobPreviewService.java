package fun.fengwk.kkstudio.core.studio.resource;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * blob webp 预览对象生成：{@code blobs/{blobId}/preview.webp} 只由本服务写入，已存在（幂等）时跳过。
 *
 * <p>调用方（上传消费 afterCommit、输出物化）决定何时 best-effort 调用；生成失败抛出异常但不破坏 blob 事实。
 */
@Slf4j
public class CanvasBlobPreviewService {

  private static final long MAX_PREVIEW_INPUT = 512L * 1024 * 1024;

  private final CanvasMediaProperties properties;
  private final SystemSettings.StorageMedia storageMedia;
  private final S3StorageService storageService;
  private final MediaProcessRunner processRunner;

  public CanvasBlobPreviewService(
      CanvasMediaProperties properties,
      SystemSettings.StorageMedia storageMedia,
      S3StorageService storageService) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.storageMedia = Objects.requireNonNull(storageMedia, "storageMedia");
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.processRunner =
        new MediaProcessRunner(Duration.ofMillis(storageMedia.canvasMediaProcessTimeoutMillis()));
  }

  /**
   * 确保 blob 存在预览对象（IMAGE/VIDEO 才生成；其它媒体类型直接跳过）。
   *
   * @return 是否需要生成（true = 已生成；false = 已存在或非图片/视频）
   */
  public boolean ensurePreview(UUID blobId, String mediaType) {
    Objects.requireNonNull(blobId, "blobId");
    if (!isPreviewable(mediaType)) {
      return false;
    }
    String previewKey = StorageObjectKeys.blobPreview(blobId);
    if (storageService.exists(previewKey)) {
      return false;
    }
    String originalKey = StorageObjectKeys.blobOriginal(blobId);
    S3ObjectMetadata originalHead = storageService.headObject(originalKey);
    try (S3ObjectStream object = storageService.readObject(originalKey)) {
      if (object.metadata().contentLength() != originalHead.contentLength()) {
        throw new IllegalArgumentException("S3 GET metadata size does not match HEAD");
      }
      if (originalHead.contentLength() <= 0L || originalHead.contentLength() > MAX_PREVIEW_INPUT) {
        throw new IllegalArgumentException("preview input size is outside the supported range");
      }
      Files.createDirectories(properties.getTempDir());
      Path workDir = Files.createTempDirectory(properties.getTempDir(), "canvas-preview-");
      try {
        Path original = workDir.resolve("original");
        copyBounded(object.inputStream(), original, MAX_PREVIEW_INPUT);
        Path preview = renderPreview(original, workDir);
        try (InputStream previewContent = Files.newInputStream(preview)) {
          storageService.putObject(previewKey, previewContent, Files.size(preview), "image/webp");
        }
        return true;
      } finally {
        deleteRecursively(workDir);
      }
    } catch (IOException error) {
      throw new UncheckedIOException("failed to generate blob preview", error);
    }
  }

  private Path renderPreview(Path original, Path workDir) {
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
                + storageMedia.thumbnailMaxDimension()
                + ":"
                + storageMedia.thumbnailMaxDimension()
                + ":force_original_aspect_ratio=decrease",
            "-frames:v",
            "1",
            "-c:v",
            "libwebp",
            "-quality",
            Integer.toString(storageMedia.thumbnailQuality()),
            "-an"));
    command.add(preview.toString());
    processRunner.run(command, stdout, stderr);
    if (!isNonEmptyFile(preview)) {
      throw new IllegalArgumentException("ffmpeg did not create preview.webp");
    }
    return preview;
  }

  private static boolean isPreviewable(String mediaType) {
    return mediaType != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"));
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

  private static boolean isNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0L;
    } catch (IOException error) {
      return false;
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
}
