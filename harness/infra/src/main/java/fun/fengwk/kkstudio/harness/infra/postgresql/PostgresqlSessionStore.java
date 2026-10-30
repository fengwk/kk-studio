package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockRank;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Session 聚合的 PostgreSQL 实现。 */
final class PostgresqlSessionStore implements SessionStore {

  private final PostgresqlHarnessTransaction tx;

  PostgresqlSessionStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  @Override
  public void insert(Session session) {
    tx.checkOpen();
    Objects.requireNonNull(session, "session");
    tx.updateJdbc(
        "insert into harness_session (id, name, created_at) values (?, ?, ?)",
        session.id(),
        session.name(),
        PostgresqlHarnessRows.timestamp(session.createdAt()));
  }

  @Override
  public Optional<Session> find(UUID id) {
    tx.checkOpen();
    return tx.queryOne(
        "select * from harness_session where id = ?", PostgresqlHarnessRows.SESSION, id);
  }

  @Override
  public void update(Session session) {
    tx.checkOpen();
    Objects.requireNonNull(session, "session");
    tx.requireLocked(LockKey.session(session.id()));
    Session stored =
        tx.sessions()
            .find(session.id())
            .orElseThrow(
                () -> new IllegalArgumentException("session " + session.id() + " does not exist"));
    Session.validateTransition(stored, session);
    int updated =
        tx.updateJdbc(
            "update harness_session set name = ? where id = ?", session.name(), session.id());
    tx.requireSingleUpdate(updated, "session", session.id());
  }

  @Override
  public Optional<Session> lockForKeyShare(UUID id) {
    tx.checkOpen();
    // 已持有的行锁重复获取不改变加锁顺序，因此不再做 rank 回退检查（幂等重锁）。
    if (!tx.isLocked(LockKey.session(id))) {
      tx.requireCanLockRank(LockRank.SESSION);
    }
    Optional<Session> session =
        tx.queryOne(
            "select * from harness_session where id = ? for key share",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> tx.lockResource(LockKey.session(id)));
    return session;
  }

  @Override
  public Optional<Session> lockForUpdate(UUID id) {
    tx.checkOpen();
    if (!tx.isLocked(LockKey.session(id))) {
      tx.requireCanLockRank(LockRank.SESSION);
    }
    Optional<Session> session =
        tx.queryOne(
            "select * from harness_session where id = ? for update",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> tx.lockResource(LockKey.session(id)));
    return session;
  }

  /** 删除指定 Session 并从事务局部缓存中驱逐该 Session 的全部 EntryPath。 */
  @Override
  public boolean delete(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    tx.entryPathCache().values().removeIf(path -> path.root().sessionId().equals(sessionId));
    return tx.updateJdbc("delete from harness_session where id = ?", sessionId) == 1;
  }
}
