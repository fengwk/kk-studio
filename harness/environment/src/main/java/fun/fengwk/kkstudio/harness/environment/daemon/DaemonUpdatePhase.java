package fun.fengwk.kkstudio.harness.environment.daemon;

/** Daemon 对一条受管更新命令上报的阶段。 */
public enum DaemonUpdatePhase {
  /** 命令已接受，即将下载与预检。 */
  ACCEPTED,
  /** 下载、SHA256/manifest 校验与磁盘配置预检已通过，可交给 OS 更新器替换/重启。 */
  PREPARED,
  /** 下载、校验或预检失败；旧二进制未改动。 */
  FAILED
}
