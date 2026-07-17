package fun.fengwk.kkstudio.core.ai.image.model;

import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 图片数据。
 *
 * @author fengwk
 */
@Data
public class ImageData {

  private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
  private static final String DEFAULT_EXTENSION = ".bin";

  private static final Map<String, String> EXTENSION_TO_MIME_TYPE = new LinkedHashMap<>();
  private static final Map<String, String> MIME_TYPE_TO_EXTENSION = new LinkedHashMap<>();

  static {
    register(".png", "image/png");
    register(".jpg", "image/jpeg");
    register(".jpeg", "image/jpeg");
    register(".webp", "image/webp");
    register(".gif", "image/gif");
    register(".bmp", "image/bmp");
    register(".svg", "image/svg+xml");
  }

  private String mimeType;
  private String base64;

  public static ImageData fromFile(File file) throws IOException {
    Objects.requireNonNull(file, "file must not be null");
    return fromFile(file.toPath());
  }

  public static ImageData fromFile(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    return fromFile(path, detectMimeType(path));
  }

  public static ImageData fromFile(Path path, String mimeType) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    byte[] bytes = Files.readAllBytes(path);
    return of(mimeType, bytes);
  }

  public static ImageData of(String mimeType, byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");

    ImageData imageData = new ImageData();
    imageData.setMimeType(normalizeMimeType(mimeType));
    imageData.setBase64(Base64.getEncoder().encodeToString(bytes));
    return imageData;
  }

  public byte[] decode() {
    Objects.requireNonNull(base64, "base64 must not be null");
    return Base64.getDecoder().decode(base64);
  }

  public Path writeTo(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return Files.write(path, decode());
  }

  public Path writeToTempFile(String prefix) throws IOException {
    Path path = Files.createTempFile(prefix, resolveExtension(mimeType));
    return Files.write(path, decode());
  }

  public String resolveExtension() {
    return resolveExtension(mimeType);
  }

  private static String detectMimeType(Path path) throws IOException {
    String mimeType = Files.probeContentType(path);
    if (!isBlank(mimeType)) {
      return mimeType;
    }

    String fileName =
        path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> entry : EXTENSION_TO_MIME_TYPE.entrySet()) {
      if (fileName.endsWith(entry.getKey())) {
        return entry.getValue();
      }
    }
    return DEFAULT_MIME_TYPE;
  }

  private static String normalizeMimeType(String mimeType) {
    if (isBlank(mimeType)) {
      return DEFAULT_MIME_TYPE;
    }
    return mimeType.trim().toLowerCase(Locale.ROOT);
  }

  private static String resolveExtension(String mimeType) {
    return MIME_TYPE_TO_EXTENSION.getOrDefault(normalizeMimeType(mimeType), DEFAULT_EXTENSION);
  }

  private static void register(String extension, String mimeType) {
    EXTENSION_TO_MIME_TYPE.put(extension, mimeType);
    MIME_TYPE_TO_EXTENSION.putIfAbsent(mimeType, extension);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
