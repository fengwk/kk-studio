package fun.fengwk.kkstudio.platform.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;

import java.util.Optional;
import java.util.UUID;

/**
 * IDE 独立 Origin 的一次性 bootstrap 票据：{@code OPEN_VIEW} 返回的 entryUrl 携带该票据，IDE host 消费后设置
 * HttpOnly/host-only cookie 再跳转官方 {@code /?workspace=<encoded>}。
 *
 * <p>票据绑定 Environment、view、受管 workspace 路径与命令进入传输时的连接代际（{@link IdeSessionIdentity}：Daemon 实例 +
 * 租约）；一次性、短时、消费即删除。{@link #revoke} 在租约失效/接管时按代际作废旧票据，避免跨进程接管后旧入口继续有效。
 */
public interface IdeBootstrapTicketService {

  String issue(IdeSessionIdentity identity, String viewId, String workspacePath);

  Optional<Ticket> consume(String ticket);

  /** 撤销某 Environment 指定租约代际下尚未消费的票据（租约失效/接管时调用）。 */
  void revoke(EnvironmentId environmentId, UUID leaseToken);

  record Ticket(IdeSessionIdentity identity, String viewId, String workspacePath) {

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }
}
