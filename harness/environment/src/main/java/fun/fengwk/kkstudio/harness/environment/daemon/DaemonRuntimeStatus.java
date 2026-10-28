package fun.fengwk.kkstudio.harness.environment.daemon;

/**
 * Daemon 当前运行状态：实际运行版本与可空的更新快照。
 *
 * <p>{@code daemonVersion} 始终来自 Daemon 上报，不能用更新目标版本填补；无更新时 {@code update} 必须明确为 null。
 */
public record DaemonRuntimeStatus(String daemonVersion, DaemonUpdateProgress update) {

  public DaemonRuntimeStatus {
    daemonVersion = DaemonCapabilities.validateDaemonVersion(daemonVersion);
  }

  /** 无更新时的显式状态。 */
  public static DaemonRuntimeStatus running(String daemonVersion) {
    return new DaemonRuntimeStatus(daemonVersion, null);
  }
}
