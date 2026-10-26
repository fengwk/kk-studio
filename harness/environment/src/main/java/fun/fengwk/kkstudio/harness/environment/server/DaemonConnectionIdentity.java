package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.Objects;

/**
 * 一个 Environment 当前 READY 连接背后的 Daemon 实例身份，也是 IDE 凭据的代际。
 *
 * <p>上层凭据（IDE 入口 bootstrap、浏览器 cookie 与反向数据流）绑定该身份：同一 Daemon 进程断线重连后继续有效（其 code-server 与视图仍在）；另一个
 * Daemon 实例在本节点接管后一律作废（见 {@link
 * EnvironmentSessionListener#onDaemonInstanceReplaced(DaemonConnectionIdentity)}）。
 */
public record DaemonConnectionIdentity(EnvironmentId environmentId, String daemonInstanceId) {

  public DaemonConnectionIdentity {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    daemonInstanceId = Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
  }
}
