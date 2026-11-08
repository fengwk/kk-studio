/**
 * 目录锁的两种可区分失败：竞争失败与等待超时。
 *
 * <p>锁实现（POSIX `flock`、Windows `LockFileEx`）与等待逻辑必须让调用方区分「已被另一个进程持有」和「真正的权限/IO 错误」：
 * 前者表示 busy，可以报告为竞争结果；后者绝不能被当成 busy 静默重试或继续。安装操作锁 helper 依赖这一区分决定退出码。
 */

/** 目录内核锁已被另一个进程持有。 */
export class DirectoryLockBusyError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DirectoryLockBusyError'
  }
}

/** 在给定期限内没有等到目录内核锁释放。 */
export class DirectoryLockTimeoutError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DirectoryLockTimeoutError'
  }
}
