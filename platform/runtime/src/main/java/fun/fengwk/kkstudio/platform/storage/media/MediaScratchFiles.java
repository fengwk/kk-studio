package fun.fengwk.kkstudio.platform.storage.media;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Shared filesystem operations for temporary media files. */
@Slf4j
final class MediaScratchFiles {

  private MediaScratchFiles() {}

  static boolean isNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0L;
    } catch (IOException error) {
      return false;
    }
  }

  static long copyBounded(InputStream content, Path target, long maxSize) {
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

  static void deleteRecursively(Path path) {
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
