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
  public static final String DEFAULT_TEXT_EXTENSION = "txt";
  public static final String JSON_EXTENSION = "json";

  private ToolArtifactPath() {}

  /**
   * 构造系统 Tool Artifact 虚拟绝对路径。
   *
   * @param threadId 会话线程 UUID
   * @param invocationId 工具调用 UUID
   * @param extension 文件扩展名（如 txt、json）
   * @return 标准化 {@link CloudPath}
   */
  public static CloudPath format(UUID threadId, UUID invocationId, String extension) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(invocationId, "invocationId");
    String ext =
        (extension == null || extension.isBlank()) ? DEFAULT_TEXT_EXTENSION : extension.trim();
    if (ext.startsWith(".")) {
      ext = ext.substring(1);
    }
    if (ext.isEmpty() || ext.contains("/") || ext.contains("\\")) {
      throw new CloudPathValidationException("Invalid artifact extension: " + extension);
    }
    String pathStr =
        String.format(
            "/%s/%s/%s/%s.%s",
            ARTIFACTS_SEGMENT,
            TOOL_RESULTS_SEGMENT,
            threadId.toString().toLowerCase(),
            invocationId.toString().toLowerCase(),
            ext.toLowerCase());
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
    try {
      UUID.fromString(segs.get(2));
    } catch (IllegalArgumentException e) {
      return false;
    }
    String fileSeg = segs.get(3);
    int dotIndex = fileSeg.lastIndexOf('.');
    if (dotIndex <= 0 || dotIndex == fileSeg.length() - 1) {
      return false;
    }
    try {
      UUID.fromString(fileSeg.substring(0, dotIndex));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** 解析 Tool Artifact 路径中的元数据。 */
  public static ToolArtifactCoordinates parse(CloudPath path) {
    if (!isToolArtifactPath(path)) {
      throw new CloudPathValidationException("Not a valid tool artifact path: " + path);
    }
    List<String> segs = path.segments();
    UUID threadId = UUID.fromString(segs.get(2));
    String fileSeg = segs.get(3);
    int dotIndex = fileSeg.lastIndexOf('.');
    UUID invocationId = UUID.fromString(fileSeg.substring(0, dotIndex));
    String ext = fileSeg.substring(dotIndex + 1);
    return new ToolArtifactCoordinates(threadId, invocationId, ext);
  }

  public record ToolArtifactCoordinates(UUID threadId, UUID invocationId, String extension) {}
}
