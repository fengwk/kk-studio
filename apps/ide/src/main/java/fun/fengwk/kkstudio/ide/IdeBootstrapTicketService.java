package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.Optional;

/**
 * IDE 同源网关的一次性 bootstrap 票据：{@code OPEN_VIEW} 返回的 entryUrl 携带该票据，同源网关消费后设置 HttpOnly 且限定 Path 的
 * cookie 再跳转官方 {@code /?workspace=<encoded>}。
 *
 * <p>票据绑定 Environment、view、受管 workspace 路径与命令进入传输时的 Daemon 实例代际（{@link
 * DaemonConnectionIdentity}）；一次性、短时、消费即删除。{@link #revoke} 在另一个 Daemon
 * 实例接管时按代际作废旧票据，避免跨进程接管后旧入口继续有效。
 */
public interface IdeBootstrapTicketService {

  String issue(DaemonConnectionIdentity identity, String viewId, String workspacePath);

  Optional<Ticket> consume(String ticket);

  /** 撤销某代际尚未消费的票据（另一个 Daemon 实例接管时调用）。 */
  void revoke(DaemonConnectionIdentity identity);

  /** 撤销某视图尚未消费的入口票据（视图关闭时调用）；同一视图再次打开时旧票据不得继续有效。 */
  void revokeView(EnvironmentId environmentId, String viewId);

  record Ticket(DaemonConnectionIdentity identity, String viewId, String workspacePath) {

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }
}
