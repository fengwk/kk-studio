package fun.fengwk.kkstudio.platform.daemon;

/**
 * Daemon 当前运行状态：实际运行版本、可空的更新快照与可空的配置应用状态。
 *
 * <p>{@code daemonVersion} 始终来自 Daemon 上报，不能用更新目标版本填补；无更新时 {@code update} 必须明确为 null。 {@code
 * configuration} 为空表示该 Daemon 尚未上报配置状态。
 */
public record DaemonRuntimeStatus(
    String daemonVersion, DaemonUpdateProgress update, DaemonConfigurationStatus configuration) {

  public DaemonRuntimeStatus {
    daemonVersion = DaemonReadyMetadata.validateDaemonVersion(daemonVersion);
  }

  /** 仅含版本与更新状态；配置状态明确未知。 */
  public DaemonRuntimeStatus(String daemonVersion, DaemonUpdateProgress update) {
    this(daemonVersion, update, null);
  }
}
