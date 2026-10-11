package fun.fengwk.kkstudio.platform.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.OneTimeTicketStore;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 进程内一次性 bootstrap 票据实现：委托 {@link OneTimeTicketStore}，仅在本节点消费。 */
public final class InMemoryIdeBootstrapTicketService implements IdeBootstrapTicketService {

  private final OneTimeTicketStore<Ticket> store = new OneTimeTicketStore<>();

  @Override
  public String issue(IdeSessionIdentity identity, String viewId, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    return store.issue(new Ticket(identity, viewId, workspacePath));
  }

  @Override
  public Optional<Ticket> consume(String ticket) {
    return store.consume(ticket);
  }

  @Override
  public void revoke(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    store.revoke(
        ticket ->
            ticket.identity().environmentId().equals(environmentId)
                && ticket.identity().leaseToken().equals(leaseToken));
  }

  /** 当前未消费票据数量（诊断/测试）。 */
  public int pendingCount() {
    return store.pendingCount();
  }
}
