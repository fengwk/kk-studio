package fun.fengwk.kkstudio.harness.common.result;

/**
 * 结构化文本工件元数据，包含确切的 UTF-8 字节数与物理行数。
 *
 * <p>普通媒体（图片、音频、二进制）不携带此元数据。
 */
public record TextArtifactMetadata(long totalBytes, long totalLines) {

  public TextArtifactMetadata {
    if (totalBytes < 0) {
      throw new IllegalArgumentException("totalBytes must not be negative");
    }
    if (totalLines < 0) {
      throw new IllegalArgumentException("totalLines must not be negative");
    }
  }
}
