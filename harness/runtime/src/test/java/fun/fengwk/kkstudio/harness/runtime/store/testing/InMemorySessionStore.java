package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Session 聚合原语：append-only 身份记录，只有显示名称可更新；行锁位于锁阶梯最底层。 */
final class InMemorySessionStore implements SessionStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemorySessionStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public void insert(Session session) {
    tx.checkOpen();
    Objects.requireNonNull(session, "session");
    HarnessStoreTime.requireMillisecondPrecision(session.createdAt());
    InMemoryHarnessTransaction.requireAbsent(state.sessions, session.id(), "session");
    state.sessions.put(session.id(), session);
  }

  @Override
  public void update(Session session) {
    tx.checkOpen();
    Objects.requireNonNull(session, "session");
    HarnessStoreTime.requireMillisecondPrecision(session.createdAt());
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.session(session.id()));
    Session stored = state.sessions.get(session.id());
    if (stored == null) {
      throw new IllegalArgumentException("session " + session.id() + " does not exist");
    }
    Session.validateTransition(stored, session);
    state.sessions.put(session.id(), session);
  }

  @Override
  public Optional<Session> find(UUID id) {
    tx.checkOpen();
    return Optional.ofNullable(state.sessions.get(id));
  }

  @Override
  public Optional<Session> lockForKeyShare(UUID id) {
    tx.checkOpen();
    // 已持有的行锁重复获取不改变加锁顺序，因此不再做 rank 回退检查（幂等重锁）。
    InMemoryHarnessLocks.LockKey lockKey = InMemoryHarnessLocks.LockKey.session(id);
    if (!tx.locks().isLocked(lockKey)) {
      tx.locks().requireCanLockRank(InMemoryHarnessLocks.LockRank.SESSION);
    }
    Session session = state.sessions.get(id);
    if (session != null) {
      tx.locks().lock(lockKey);
    }
    return Optional.ofNullable(session);
  }

  @Override
  public Optional<Session> lockForUpdate(UUID id) {
    // 单 monitor 参考实现没有真正的并发，但为与生产实现一致的锁序记账，FOR UPDATE 与 KEY SHARE 走同一路径。
    return lockForKeyShare(id);
  }

  @Override
  public boolean delete(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    return state.sessions.remove(sessionId) != null;
  }
}
