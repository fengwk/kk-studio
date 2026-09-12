package fun.fengwk.kkstudio.platform.cloudfs.domain;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tool Artifact 持久化路径规范工具。
 *
 * <p>规范路径格式：{@code /.artifacts/tool-results/{threadId}/{invocationId}.{extension}}。
 */
public final class ToolArtifactPath {

  public static final String ARTIFACTS_SEGMENT = ".artifacts";
  public static final String TOOL_RESULTS_SEGMENT = "tool-results";
  public static final String EXTENSION_TXT = "txt";
  public static final String EXTENSION_JSON = "json";

  private ToolArtifactPath() {}

  /**
   * 构造系统 Tool Artifact 虚拟绝对路径。
   *
   * @param threadId 会话线程 UUID
   * @param invocationId 工具调用 UUID
   * @param extension 文件扩展名，仅接受精确 "txt" 或 "json"
   * @return 标准化 {@link CloudPath}
   */
  public static CloudPath format(UUID threadId, UUID invocationId, String extension) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(extension, "extension");

    if (!EXTENSION_TXT.equals(extension) && !EXTENSION_JSON.equals(extension)) {
      throw new CloudPathValidationException(
          "Invalid artifact extension: '" + extension + "', must be 'txt' or 'json'");
    }

    String pathStr =
        String.format(
            "/%s/%s/%s/%s.%s",
            ARTIFACTS_SEGMENT, TOOL_RESULTS_SEGMENT, threadId, invocationId, extension);
    return CloudPath.of(pathStr);
  }

  /** 判断指定路径是否为格式合法的 Tool Artifact 文件路径。 */
  public static boolean isToolArtifactPath(CloudPath path) {
    if (path == null) {
      return false;
    }
    List<String> segs = path.segments();
    if (segs.size() != 4) {
      return false;
    }
    if (!ARTIFACTS_SEGMENT.equals(segs.get(0)) || !TOOL_RESULTS_SEGMENT.equals(segs.get(1))) {
      return false;
    }
    if (parseCanonicalUuid(segs.get(2)) == null) {
      return false;
    }
    String fileSeg = segs.get(3);
    int dotIndex = fileSeg.lastIndexOf('.');
    if (dotIndex <= 0 || dotIndex == fileSeg.length() - 1) {
      return false;
    }
    if (parseCanonicalUuid(fileSeg.substring(0, dotIndex)) == null) {
      return false;
    }
    String ext = fileSeg.substring(dotIndex + 1);
    return EXTENSION_TXT.equals(ext) || EXTENSION_JSON.equals(ext);
  }

  /** 解析 Tool Artifact 路径中的元数据。 */
  public static ToolArtifactCoordinates parse(CloudPath path) {
    if (path == null) {
      throw new CloudPathValidationException("Path must not be null");
    }
    List<String> segs = path.segments();
    if (segs.size() != 4
        || !ARTIFACTS_SEGMENT.equals(segs.get(0))
        || !TOOL_RESULTS_SEGMENT.equals(segs.get(1))) {
      throw new CloudPathValidationException("Not a valid tool artifact path: " + path);
    }
    UUID threadId = parseCanonicalUuid(segs.get(2));
    if (threadId == null) {
      throw new CloudPathValidationException(
          "Invalid canonical thread UUID in artifact path: " + path);
    }
    String fileSeg = segs.get(3);
    int dotIndex = fileSeg.lastIndexOf('.');
    if (dotIndex <= 0 || dotIndex == fileSeg.length() - 1) {
      throw new CloudPathValidationException("Invalid artifact file name in path: " + path);
    }
    UUID invocationId = parseCanonicalUuid(fileSeg.substring(0, dotIndex));
    if (invocationId == null) {
      throw new CloudPathValidationException(
          "Invalid canonical invocation UUID in artifact path: " + path);
    }
    String ext = fileSeg.substring(dotIndex + 1);
    if (!EXTENSION_TXT.equals(ext) && !EXTENSION_JSON.equals(ext)) {
      throw new CloudPathValidationException(
          "Invalid artifact extension in path: " + path + ", must be 'txt' or 'json'");
    }
    return new ToolArtifactCoordinates(threadId, invocationId, ext);
  }

  private static UUID parseCanonicalUuid(String str) {
    if (str == null || str.length() != 36) {
      return null;
    }
    try {
      UUID uuid = UUID.fromString(str);
      if (uuid.toString().equals(str)) {
        return uuid;
      }
      return null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public record ToolArtifactCoordinates(UUID threadId, UUID invocationId, String extension) {}
}
