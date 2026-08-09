package fun.fengwk.kkstudio.core.studio.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

/** 已落盘并完成 probe/preview 的媒体结果；关闭时删除整个临时工作目录。 */
public final class CanvasProcessedMedia implements AutoCloseable {

  private final Path workDir;
  private final Path originalPath;
  private final Path previewPath;
  private final long size;
  private final String mediaType;
  private final String metadataJson;

  CanvasProcessedMedia(
      Path workDir,
      Path originalPath,
      Path previewPath,
      long size,
      String mediaType,
      String metadataJson) {
    this.workDir = Objects.requireNonNull(workDir, "workDir");
    this.originalPath = Objects.requireNonNull(originalPath, "originalPath");
    this.previewPath = previewPath;
    this.size = size;
    this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    this.metadataJson = Objects.requireNonNull(metadataJson, "metadataJson");
  }

  public Path originalPath() {
    return originalPath;
  }

  public Path previewPath() {
    return previewPath;
  }

  public long size() {
    return size;
  }

  public String mediaType() {
    return mediaType;
  }

  public String metadataJson() {
    return metadataJson;
  }

  @Override
  public void close() {
    deleteRecursively(workDir);
  }

  static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new CanvasTempCleanupException(e);
                }
              });
    } catch (IOException e) {
      throw new IllegalStateException("Failed to clean Canvas media temp directory", e);
    } catch (CanvasTempCleanupException e) {
      throw new IllegalStateException("Failed to clean Canvas media temp directory", e.getCause());
    }
  }

  private static final class CanvasTempCleanupException extends RuntimeException {

    private CanvasTempCleanupException(IOException cause) {
      super(cause);
    }
  }
}
