package fun.fengwk.kkstudio.platform.daemon;

/**
 * 当前 Daemon 进程的运行配置状态。
 *
 * <p>{@code appliedVersion} 为空表示当前进程尚无可用配置；存在时 {@code proxyUrl} 为空表示显式直连。{@code error}
 * 是最近一次配置应用失败的稳定规则结果，不回显 token、命令参数或原始输入。
 */
public record DaemonConfigurationStatus(
    Long appliedVersion, String proxyUrl, DaemonConfigurationError error) {

  public DaemonConfigurationStatus {
    if (appliedVersion != null && appliedVersion < 0L) {
      throw new IllegalArgumentException("appliedVersion must be non-negative");
    }
    if (proxyUrl != null && appliedVersion == null) {
      throw new IllegalArgumentException("proxyUrl requires an appliedVersion");
    }
  }
}
