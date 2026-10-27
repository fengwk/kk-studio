package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.Objects;

/**
 * READY v6 的完整 wire payload：宿主 metadata 与必需的 runtimeStatus。
 *
 * <p>宿主 {@code daemonVersion} 与 runtimeStatus 内的实际运行版本必须一致；Server 只把宿主 metadata 写入 {@code
 * runtime_info}，当前运行状态仅保存在内存连接代际中。
 */
public record DaemonReady(DaemonCapabilities capabilities, DaemonRuntimeSnapshot runtimeStatus) {

  public DaemonReady {
    capabilities = Objects.requireNonNull(capabilities, "capabilities");
    runtimeStatus = Objects.requireNonNull(runtimeStatus, "runtimeStatus");
    if (!capabilities.daemonVersion().equals(runtimeStatus.status().daemonVersion())) {
      throw new IllegalArgumentException(
          "runtimeStatus.daemonVersion must match READY daemonVersion");
    }
  }
}
