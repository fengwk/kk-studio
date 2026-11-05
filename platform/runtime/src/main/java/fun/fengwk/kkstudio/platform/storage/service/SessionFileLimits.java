package fun.fengwk.kkstudio.platform.storage.service;

/**
 * 单个 Session 的文件配额：文件数、单文件字节数与内容总字节数。
 *
 * <p>默认值用于生产；测试与受控部署可传入更小的配额，配额判断与写入在同一事务、同一 advisory lock 下完成。
 *
 * @param maxFilesPerSession Session 内文件数上限
 * @param maxSingleFileBytes 单文件内容字节上限
 * @param maxTotalBytesPerSession Session 内容总字节上限
 */
public record SessionFileLimits(
    int maxFilesPerSession, long maxSingleFileBytes, long maxTotalBytesPerSession) {

  public static final SessionFileLimits DEFAULT =
      new SessionFileLimits(2000, 8 * 1024 * 1024L, 64 * 1024 * 1024L);

  public SessionFileLimits {
    if (maxFilesPerSession <= 0) {
      throw new IllegalArgumentException(
          "maxFilesPerSession must be positive: " + maxFilesPerSession);
    }
    if (maxSingleFileBytes <= 0 || maxSingleFileBytes > maxTotalBytesPerSession) {
      throw new IllegalArgumentException(
          "maxSingleFileBytes must be positive and not exceed maxTotalBytesPerSession: "
              + maxSingleFileBytes);
    }
  }
}
