package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

/**
 * 格式化模型可见的 Tool Artifact 声明。
 *
 * <p>声明位于 raw preview 之前，包含 canonical Cloud path、精确字节数与行数、 以及 {@code cloud_grep}/{@code cloud_read}
 * 使用提示；绝不包含 blobId、S3 key、预签名 URL 或临时路径。
 */
public final class ToolArtifactOutputFormatter {

  private ToolArtifactOutputFormatter() {}

  /**
   * 格式化模型可见声明与 raw preview。
   *
   * @param artifactPath 规范 CFS 工件路径
   * @param totalBytes 确切总字节数
   * @param totalLines 确切总行数
   * @param preview 原始 preview 前缀（可空）
   * @return 模型可见文本
   */
  public static String formatNotice(
      String artifactPath, long totalBytes, long totalLines, String preview) {
    Objects.requireNonNull(artifactPath, "artifactPath");
    StringBuilder sb = new StringBuilder();
    sb.append(
        "[Output externalized. The preview below is incomplete; do not treat it as the full"
            + " result.\n\n");
    sb.append("Full output: ").append(artifactPath).append("\n");
    sb.append("Size: ")
        .append(totalBytes)
        .append(" bytes, ")
        .append(totalLines)
        .append(" lines\n\n");
    sb.append("Use cloud_grep to locate relevant content, then cloud_read with offset/limit.]");
    sb.append("\n\n--- preview ---");
    if (preview != null && !preview.isEmpty()) {
      sb.append("\n").append(preview);
    }
    return sb.toString();
  }
}
