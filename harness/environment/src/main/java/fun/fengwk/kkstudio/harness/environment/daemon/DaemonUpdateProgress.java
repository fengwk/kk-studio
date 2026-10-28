package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * Daemon 当前受管更新的内存快照：目标版本与发行归档下载进度。
 *
 * <p>字节计数只统计发行归档，非负且不超过 JavaScript 安全整数上界；总量未知时 {@code totalBytes} 为 null。100% 只表示下载完成，
 * 校验与替换期间对象仍然存在。
 */
public record DaemonUpdateProgress(String targetVersion, long downloadedBytes, Long totalBytes) {

  /** JavaScript {@code Number.MAX_SAFE_INTEGER}：wire 上的字节计数必须可被浏览器无损表示。 */
  public static final long MAX_SAFE_BYTE_COUNT = 9_007_199_254_740_991L;

  public DaemonUpdateProgress {
    targetVersion = DaemonCapabilities.validateDaemonVersion(targetVersion);
    if (downloadedBytes < 0 || downloadedBytes > MAX_SAFE_BYTE_COUNT) {
      throw new IllegalArgumentException("downloadedBytes is outside the safe integer range");
    }
    if (totalBytes != null) {
      if (totalBytes < 0 || totalBytes > MAX_SAFE_BYTE_COUNT) {
        throw new IllegalArgumentException("totalBytes is outside the safe integer range");
      }
      if (totalBytes < downloadedBytes) {
        throw new IllegalArgumentException("totalBytes must not be smaller than downloadedBytes");
      }
    }
  }
}
