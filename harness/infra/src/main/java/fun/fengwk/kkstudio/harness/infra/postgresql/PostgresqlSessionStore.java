package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockRank;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Session 聚合的 PostgreSQL 实现。 */
final class PostgresqlSessionStore extends PostgresqlHarnessView implements SessionStore {

  PostgresqlSessionStore(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public void insert(Session session) {
    checkOpen();
    Objects.requireNonNull(session, "session");
    updateJdbc(
        "insert into harness_session (id, name, created_at) values (?, ?, ?)",
        session.id(),
        session.name(),
        PostgresqlHarnessRows.timestamp(session.createdAt()));
  }

  @Override
  public Optional<Session> find(UUID id) {
    checkOpen();
    return queryOne(
        "select * from harness_session where id = ?", PostgresqlHarnessRows.SESSION, id);
  }

  @Override
  public void update(Session session) {
    checkOpen();
    Objects.requireNonNull(session, "session");
    requireLocked(LockKey.session(session.id()));
    Session stored =
        tx.sessions()
            .find(session.id())
            .orElseThrow(
                () -> new IllegalArgumentException("session " + session.id() + " does not exist"));
    Session.validateTransition(stored, session);
    int updated =
        updateJdbc(
            "update harness_session set name = ? where id = ?", session.name(), session.id());
    requireSingleUpdate(updated, "session", session.id());
  }

  @Override
  public Optional<Session> lockForKeyShare(UUID id) {
    checkOpen();
    // 已持有的行锁重复获取不改变加锁顺序，因此不再做 rank 回退检查（幂等重锁）。
    if (!isLocked(LockKey.session(id))) {
      requireCanLockRank(LockRank.SESSION);
    }
    Optional<Session> session =
        queryOne(
            "select * from harness_session where id = ? for key share",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> lockResource(LockKey.session(id)));
    return session;
  }

  @Override
  public Optional<Session> lockForUpdate(UUID id) {
    checkOpen();
    if (!isLocked(LockKey.session(id))) {
      requireCanLockRank(LockRank.SESSION);
    }
    Optional<Session> session =
        queryOne(
            "select * from harness_session where id = ? for update",
            PostgresqlHarnessRows.SESSION,
            id);
    session.ifPresent(ignored -> lockResource(LockKey.session(id)));
    return session;
  }

  /** 删除指定 Session 并从事务局部缓存中驱逐该 Session 的全部 EntryPath。 */
  @Override
  public boolean delete(UUID sessionId) {
    checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    entryPathCache().values().removeIf(path -> path.root().sessionId().equals(sessionId));
    return updateJdbc("delete from harness_session where id = ?", sessionId) == 1;
  }
}
