package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.OneTimeTicketStore;

import java.util.Objects;
import java.util.Optional;

/** 进程内一次性 bootstrap 票据实现：委托 {@link OneTimeTicketStore}，仅在本节点消费。 */
public final class InMemoryIdeBootstrapTicketService implements IdeBootstrapTicketService {

  private final OneTimeTicketStore<Ticket> store = new OneTimeTicketStore<>();

  @Override
  public String issue(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    return store.issue(new Ticket(identity, viewId, bindingEpoch, workspacePath));
  }

  @Override
  public Optional<Ticket> consume(String ticket) {
    return store.consume(ticket);
  }

  @Override
  public void revoke(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    store.revoke(ticket -> ticket.identity().equals(identity));
  }

  @Override
  public void revokeView(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    store.revoke(
        ticket ->
            ticket.identity().environmentId().equals(environmentId)
                && ticket.viewId().equals(viewId));
  }

  /** 当前未消费票据数量（诊断/测试）。 */
  public int pendingCount() {
    return store.pendingCount();
  }
}
