package fun.fengwk.kkstudio.platform.cloudfs.domain;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Cloud File System 虚拟绝对路径值对象。
 *
 * <p>规范：
 *
 * <ul>
 *   <li>必须以 {@code /} 开头，根路径固定为 {@code /}；
 *   <li>唯一分隔符为 {@code /}，大小写敏感；
 *   <li>输入必须为 Unicode NFC 规范格式，非 NFC 格式直接拒绝；
 *   <li>禁止空 segment（如 {@code //}）、禁止 {@code .} 或 {@code ..}、非根路径禁止尾随 {@code /}；
 *   <li>禁止反斜杠 {@code \} 与 ISO 控制字符（C0 与 C1，如 U+0000..U+001F、U+007F..U+009F）；
 *   <li>禁止未配对 surrogate；
 *   <li>使用严格 UTF-8 编码，单个 segment UTF-8 字节长度至多 255 字节，完整路径至多 2048 字节；
 *   <li>不得使用宿主 {@link java.nio.file.Path} 进行解释。
 * </ul>
 */
public final class CloudPath implements Comparable<CloudPath> {

  public static final int MAX_TOTAL_BYTES = 2048;
  public static final int MAX_SEGMENT_BYTES = 255;
  public static final String ROOT_PATH_STRING = "/";
  public static final String ARTIFACTS_ROOT_SEGMENT = ".artifacts";

  private static final CloudPath ROOT = new CloudPath(ROOT_PATH_STRING, List.of());

  private final String canonicalPath;
  private final List<String> segments;

  private CloudPath(String canonicalPath, List<String> segments) {
    this.canonicalPath = canonicalPath;
    this.segments = Collections.unmodifiableList(segments);
  }

  /** 返回根目录路径 {@code /}。 */
  public static CloudPath root() {
    return ROOT;
  }

  /**
   * 解析并校验虚拟绝对路径字符串。
   *
   * @param rawPath 待解析的绝对路径字符串
   * @return 标准化 {@link CloudPath}
   * @throws CloudPathValidationException 路径不符合规范时抛出
   */
  public static CloudPath of(String rawPath) {
    if (rawPath == null) {
      throw new CloudPathValidationException("Path must not be null");
    }
    if (rawPath.isEmpty()) {
      throw new CloudPathValidationException("Path must not be empty");
    }
    if (!rawPath.startsWith("/")) {
      throw new CloudPathValidationException(
          "Path must be absolute and start with '/': " + rawPath);
    }
    if (rawPath.indexOf('\\') >= 0) {
      throw new CloudPathValidationException("Path must not contain backslash: " + rawPath);
    }

    for (int i = 0; i < rawPath.length(); ) {
      char c = rawPath.charAt(i);
      if (Character.isSurrogate(c)) {
        if (!Character.isHighSurrogate(c)
            || i + 1 >= rawPath.length()
            || !Character.isLowSurrogate(rawPath.charAt(i + 1))) {
          throw new CloudPathValidationException("Path contains unpaired surrogate: " + rawPath);
        }
      }
      int cp = rawPath.codePointAt(i);
      if (Character.isISOControl(cp)) {
        throw new CloudPathValidationException(
            String.format("Path must not contain ISO control character U+%04X: %s", cp, rawPath));
      }
      i += Character.charCount(cp);
    }

    if (!Normalizer.isNormalized(rawPath, Normalizer.Form.NFC)) {
      throw new CloudPathValidationException(
          "Path must be normalized in Unicode NFC form: " + rawPath);
    }

    byte[] utf8Bytes;
    try {
      utf8Bytes = StrictUtf8.encode(rawPath, "path");
    } catch (CloudFileSystemValidationException e) {
      throw new CloudPathValidationException(e.getMessage(), e);
    }
    if (utf8Bytes.length > MAX_TOTAL_BYTES) {
      throw new CloudPathValidationException(
          String.format(
              "Path length (%d bytes) exceeds maximum limit of %d bytes: %s",
              utf8Bytes.length, MAX_TOTAL_BYTES, rawPath));
    }
    if (rawPath.equals(ROOT_PATH_STRING)) {
      return ROOT;
    }
    if (rawPath.endsWith("/")) {
      throw new CloudPathValidationException(
          "Non-root path must not end with trailing slash: " + rawPath);
    }

    String[] rawSegments = rawPath.substring(1).split("/", -1);
    List<String> segmentList = new ArrayList<>(rawSegments.length);
    for (String segment : rawSegments) {
      if (segment.isEmpty()) {
        throw new CloudPathValidationException("Path must not contain empty segment: " + rawPath);
      }
      if (".".equals(segment) || "..".equals(segment)) {
        throw new CloudPathValidationException(
            "Path must not contain dot or dot-dot segment: " + rawPath);
      }
      byte[] segBytes;
      try {
        segBytes = StrictUtf8.encode(segment, "path segment '" + segment + "'");
      } catch (CloudFileSystemValidationException e) {
        throw new CloudPathValidationException(e.getMessage(), e);
      }
      if (segBytes.length > MAX_SEGMENT_BYTES) {
        throw new CloudPathValidationException(
            String.format(
                "Path segment '%s' (%d bytes) exceeds maximum limit of %d bytes: %s",
                segment, segBytes.length, MAX_SEGMENT_BYTES, rawPath));
      }
      segmentList.add(segment);
    }
    return new CloudPath(rawPath, segmentList);
  }

  /** 返回标准化绝对路径字符串。 */
  public String value() {
    return canonicalPath;
  }

  /** 判断当前路径是否为根目录 {@code /}。 */
  public boolean isRoot() {
    return segments.isEmpty();
  }

  /** 获取父目录路径；若当前为根目录，则返回 {@code null}。 */
  public CloudPath parent() {
    if (isRoot()) {
      return null;
    }
    if (segments.size() == 1) {
      return ROOT;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size() - 1; i++) {
      sb.append('/').append(segments.get(i));
    }
    return new CloudPath(sb.toString(), segments.subList(0, segments.size() - 1));
  }

  /** 获取当前路径末尾的 segment 名称；若为根目录，则返回空字符串 {@code ""}。 */
  public String name() {
    if (isRoot()) {
      return "";
    }
    return segments.get(segments.size() - 1);
  }

  /** 返回分段列表。 */
  public List<String> segments() {
    return segments;
  }

  /**
   * 在当前路径下解析子路径名称。
   *
   * @param childSegment 子分段名称
   * @return 子路径 {@link CloudPath}
   */
  public CloudPath child(String childSegment) {
    if (childSegment == null || childSegment.isEmpty()) {
      throw new CloudPathValidationException("Child segment must not be null or empty");
    }
    if (childSegment.indexOf('/') >= 0 || childSegment.indexOf('\\') >= 0) {
      throw new CloudPathValidationException(
          "Child segment must not contain slashes: " + childSegment);
    }
    if (isRoot()) {
      return CloudPath.of("/" + childSegment);
    }
    return CloudPath.of(canonicalPath + "/" + childSegment);
  }

  /** 判断当前路径是否是指定祖先路径的严格后代。 */
  public boolean isDescendantOf(CloudPath ancestor) {
    if (ancestor == null) {
      return false;
    }
    if (ancestor.isRoot()) {
      return !this.isRoot();
    }
    if (this.segments.size() <= ancestor.segments.size()) {
      return false;
    }
    for (int i = 0; i < ancestor.segments.size(); i++) {
      if (!this.segments.get(i).equals(ancestor.segments.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** 判断当前路径是否属于系统保留的 Tool Artifact 路径树（即 {@code /.artifacts} 及其子路径）。 */
  public boolean isArtifactPath() {
    return !segments.isEmpty() && ARTIFACTS_ROOT_SEGMENT.equals(segments.get(0));
  }

  @Override
  public int compareTo(CloudPath other) {
    return this.canonicalPath.compareTo(other.canonicalPath);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CloudPath other)) {
      return false;
    }
    return Objects.equals(canonicalPath, other.canonicalPath);
  }

  @Override
  public int hashCode() {
    return canonicalPath.hashCode();
  }

  @Override
  public String toString() {
    return canonicalPath;
  }
}
