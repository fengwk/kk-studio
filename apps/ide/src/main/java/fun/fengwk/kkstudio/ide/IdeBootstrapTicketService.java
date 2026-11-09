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
 * IdeGenerationState} 与唯一权威视图快照。撤销按完整 {@code (Daemon 身份, viewId, bindingEpoch)} 精确进行，旧 epoch
 * 的回滚或清理不能删除新绑定票据。
 */
public class IdeBootstrapTicketService {

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

  /** 只撤销该 Daemon 进程该视图该 epoch 尚未消费的入口票据。 */
  public void revoke(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    store.revoke(
        ticket ->
            ticket.identity().equals(identity)
                && ticket.viewId().equals(viewId)
                && ticket.bindingEpoch() == bindingEpoch);
  }

  /** 只单向撤销同 view 严格早于 {@code currentBindingEpoch} 的遗留票据；当前与更新 epoch 永不受影响。 */
  public void revokeOlderThan(
      DaemonConnectionIdentity identity, String viewId, long currentBindingEpoch) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    if (currentBindingEpoch <= 0) {
      throw new IllegalArgumentException("currentBindingEpoch must be positive");
    }
    store.revoke(
        ticket ->
            ticket.identity().equals(identity)
                && ticket.viewId().equals(viewId)
                && ticket.bindingEpoch() < currentBindingEpoch);
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
