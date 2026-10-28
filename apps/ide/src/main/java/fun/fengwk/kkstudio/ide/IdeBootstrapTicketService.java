package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.Optional;

/**
 * IDE 同源网关的一次性 bootstrap 票据：claim 成功后的 entryUrl 携带该票据，网关消费后设置绑定 view+epoch 的 HttpOnly cookie。
 *
 * <p>票据绑定 Environment、Daemon 实例、viewId、bindingEpoch 与受管 workspace，一次性且短时；消费时仍须复核当前 {@link
 * IdeGenerationState} 与视图绑定。关闭/恢复换代/实例接管都会撤销尚未消费的旧票据。
 */
public interface IdeBootstrapTicketService {

  String issue(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath);

  Optional<Ticket> consume(String ticket);

  /** 撤销某代际尚未消费的票据（另一个 Daemon 实例接管时调用）。 */
  void revoke(DaemonConnectionIdentity identity);

  /** 撤销某视图全部尚未消费的入口票据（关闭、恢复换代时调用）。 */
  void revokeView(EnvironmentId environmentId, String viewId);

  record Ticket(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {

    public Ticket {
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
    }

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }
}
