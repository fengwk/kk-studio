package fun.fengwk.kkstudio.platform.environment.update;

/** 一次受管更新操作的持久阶段。 */
public enum EnvironmentUpdatePhase {
  /** 已接受请求并持久化，尚未确认 Daemon 接收命令。 */
  PENDING,
  /** 命令已下发，Daemon 正在下载/校验/预检。 */
  RUNNING,
  /** Daemon 已写出 handoff，等待 OS 更新器替换与重启。 */
  PREPARED,
  /** 更新后 Daemon 以目标版本重新 READY，确认成功。 */
  SUCCEEDED,
  /** 准备失败或命令不可达；旧二进制未改动。 */
  FAILED,

  /** 仅由读取派生：推进中的操作（PENDING/RUNNING/PREPARED）在宿主当前不可达时表达「等待重连确认」；绝不持久化，也不代表失败。 */
  UNKNOWN;

  /** 是否为仍在推进的阶段。 */
  public boolean isActive() {
    return this == PENDING || this == RUNNING || this == PREPARED;
  }

  /** 是否为终态阶段。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED;
  }
}
