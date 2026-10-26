package fun.fengwk.kkstudio.ide.stream;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.server.OneTimeTicketStore;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 进程内一次性 IDE 流 ticket 实现：委托 {@link OneTimeTicketStore}，绑定值编码为 Daemon 实例代际 + 模式。
 *
 * <p>只在本节点（当前 owner）内有效，不做跨节点复制：跨节点请求先由 owner 定位再本地消费。
 */
public final class InMemoryIdeStreamTicketService implements IdeStreamTicketService {

  private final OneTimeTicketStore<Consumed> store;

  public InMemoryIdeStreamTicketService() {
    this(new OneTimeTicketStore<>());
  }

  public InMemoryIdeStreamTicketService(SecureRandom random, Clock clock, Duration ttl) {
    this(new OneTimeTicketStore<>(random, clock, ttl));
  }

  private InMemoryIdeStreamTicketService(OneTimeTicketStore<Consumed> store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Issued issue(DaemonConnectionIdentity identity, IdeStreamMode mode) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(mode, "mode");
    String ticket = store.issue(new Consumed(identity, mode));
    return new Issued(ticket, mode);
  }

  @Override
  public Optional<Consumed> consume(String ticket) {
    return store.consume(ticket);
  }

  @Override
  public void revoke(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    store.revoke(consumed -> consumed.identity().equals(identity));
  }

  /** 当前未消费 ticket 数量（用于测试与诊断，不暴露 ticket 值）。 */
  public int pendingCount() {
    return store.pendingCount();
  }
}
