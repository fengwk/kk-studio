package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.OneTimeTicketStore;

import java.util.Objects;
import java.util.Optional;

/**
 * IDE 同源网关的一次性 bootstrap 票据：claim 成功后的 entryUrl 携带该票据，网关消费后设置绑定 view+epoch 的 HttpOnly cookie。
 *
 * <p>票据绑定 Environment、Daemon 实例、viewId、bindingEpoch 与受管 workspace，一次性且短时；消费时仍须复核当前 {@link
 * IdeGenerationState} 与视图绑定。关闭/恢复换代/实例接管都会撤销尚未消费的旧票据。
 */
public final class IdeBootstrapTicketService {

  private final OneTimeTicketStore<Ticket> store = new OneTimeTicketStore<>();

  public String issue(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    return store.issue(new Ticket(identity, viewId, bindingEpoch, workspacePath));
  }

  public Optional<Ticket> consume(String ticket) {
    return store.consume(ticket);
  }

  /** 撤销某视图全部尚未消费的入口票据（关闭、恢复换代时调用）。 */
  public void revokeView(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    store.revoke(
        ticket ->
            ticket.identity().environmentId().equals(environmentId)
                && ticket.viewId().equals(viewId));
  }

  int pendingCount() {
    return store.pendingCount();
  }

  public record Ticket(
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
