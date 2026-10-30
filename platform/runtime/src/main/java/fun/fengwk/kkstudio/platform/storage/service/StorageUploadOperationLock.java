package fun.fengwk.kkstudio.platform.storage.service;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.storage.error.StorageConflictException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个 upload 的「对象写入 + 绑定」与「后台清理」之间的互斥边界。
 *
 * <p>complete/stage 在写临时/候选对象并绑定上传事实的整个窗口内独占持锁；后台清理（{@link StorageUploadService#expireOnce()}）在
 * claim 之前非阻塞尝试同一把锁，拿不到就跳过本轮 —— 绝不在无锁状态下删除任何清理证据（临时对象、 候选对象或上传行）。锁只串行化同一个 upload
 * 的操作，<b>不参与数据库事务</b>：持锁连接独立于业务连接池，持锁期间禁止在数据库事务或行锁内执行 S3 I/O 的约定不变。
 *
 * <p>调用顺序约定：必须在任何业务事务/行锁之前取锁，否则并发路径可能形成「持行锁等锁、持锁等行锁」的死锁环。
 *
 * <p>实现基于 PostgreSQL 会话级 advisory lock。
 *
 * <p>锁使用「固定 storage namespace + UUID 稳定折叠成 32bit key」的双 int key 空间：同一 UUID 在任何进程、任何版本都落在同一把锁上（多节点天然
 * 互斥），不同 UUID 的理论碰撞只会让两个 upload 的写窗口额外串行，不影响正确性。
 *
 * <p>双 int key 空间是全局共享的：Harness runtime 的树锁用单 bigint key（天然隔离），Join 准入锁用 {@code
 * 0x6B6B5354/0x6A6F696E}。本类固定使用不同的 {@link #NAMESPACE}，避免与它们在同一把 advisory 锁上意外串行。
 *
 * <p>每次 acquire 用一条独立物理会话（autocommit，不开任何业务事务、不持行锁）。阻塞取锁受会话局部 {@code statement_timeout} 约束： 超过预算
 * Postgres 会取消这条语句并返回 {@link StorageConflictException}，绝不让请求线程无限挂起；该超时只作用于这条专用会话，不修改业务池或实例级设置。
 * close 时显式解锁并关闭会话，即使调用方崩溃或忘记解锁，会话结束也会由 PostgreSQL 释放锁。
 *
 * @author fengwk
 */
@Slf4j
public class StorageUploadOperationLock {

  /** 固定 namespace：与 harness runtime 的树锁/准入锁 namespace 都不同，避免跨子域在同一把 advisory 锁上串行。 */
  static final int NAMESPACE = 0x53544C4B;

  // void 返回的 advisory lock 函数不能直接取值：用常量 true 选行，同时保留阻塞语义。
  private static final String ACQUIRE_SQL = "select true from pg_advisory_lock(?::int, ?::int)";
  private static final String TRY_ACQUIRE_SQL = "select pg_try_advisory_lock(?::int, ?::int)";
  private static final String RELEASE_SQL = "select pg_advisory_unlock(?::int, ?::int)";
  private static final String SET_TIMEOUT_SQL = "select set_config('statement_timeout', ?, false)";

  /** Postgres 取消语句（statement_timeout 到点）的 SQLSTATE。 */
  private static final String QUERY_CANCELED_SQL_STATE = "57014";

  private final StorageLockConnections connections;
  private final Duration acquireTimeout;

  public StorageUploadOperationLock(StorageLockConnections connections, Duration acquireTimeout) {
    this.connections = Objects.requireNonNull(connections, "connections must not be null");
    Objects.requireNonNull(acquireTimeout, "acquireTimeout must not be null");
    if (acquireTimeout.isZero() || acquireTimeout.isNegative()) {
      throw new IllegalArgumentException("acquireTimeout must be positive");
    }
    this.acquireTimeout = acquireTimeout;
  }

  /**
   * 阻塞获取同一 upload 的独占锁：持锁方完成写对象与绑定后才会返回。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageConflictException
   *     等待超过取锁预算，上传仍被其它方占用（可重试）
   * @throws IllegalStateException 锁基础设施不可用（连接或 SQL 失败）
   */
  public Handle acquire(UUID uploadId) {
    return lock(uploadId, false);
  }

  /**
   * 非阻塞尝试获取同一 upload 的独占锁：已被占用时返回 null，调用方必须跳过本轮而不是等待（后台清理路径使用）。
   *
   * @throws IllegalStateException 锁基础设施不可用（连接或 SQL 失败）
   */
  public Handle tryAcquire(UUID uploadId) {
    return lock(uploadId, true);
  }

  /**
   * UUID -> 稳定 32bit key：折叠 msb/lsb，只依赖 UUID 本身，跨进程与跨版本一致。
   *
   * <p>碰撞概率极低且只导致额外串行，因此不需要再加二次确认。namespace 单独占位，保证 storage 锁不会与其它子域的 64bit advisory 碰撞。
   */
  static int advisoryKey(UUID uploadId) {
    return (int) (uploadId.getMostSignificantBits() ^ uploadId.getLeastSignificantBits());
  }

  private Handle lock(UUID uploadId, boolean nonBlocking) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    int key = advisoryKey(uploadId);
    Connection connection = openConnection();
    boolean handedOff = false;
    try {
      connection.setAutoCommit(true);
      if (!nonBlocking) {
        applyStatementTimeout(connection);
      }
      if (!callBoolean(connection, nonBlocking ? TRY_ACQUIRE_SQL : ACQUIRE_SQL, key)) {
        return null;
      }
      SessionHandle handle = new SessionHandle(connection, key);
      handedOff = true;
      return handle;
    } catch (SQLException error) {
      if (QUERY_CANCELED_SQL_STATE.equals(error.getSQLState())) {
        throw new StorageConflictException("upload " + uploadId + " is busy; retry later");
      }
      throw new IllegalStateException("failed to acquire storage upload lock", error);
    } finally {
      // 未交出连接的任何路径（含 SQL 失败与 Error）都必须关闭会话，绝不遗留 DriverManager 连接。
      if (!handedOff) {
        closeQuietly(connection);
      }
    }
  }

  private Connection openConnection() {
    try {
      return connections.open();
    } catch (SQLException error) {
      throw new IllegalStateException("failed to open a storage upload lock connection", error);
    }
  }

  /** 只在阻塞取锁会话上设置局部 statement_timeout：到点由 Postgres 取消语句，而不是无限等待。 */
  private void applyStatementTimeout(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SET_TIMEOUT_SQL)) {
      statement.setString(1, Long.toString(acquireTimeout.toMillis()));
      statement.execute();
    }
  }

  private static boolean callBoolean(Connection connection, String sql, int key)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, NAMESPACE);
      statement.setInt(2, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException error) {
      log.warn("failed to close a storage upload lock connection");
    }
  }

  private final class SessionHandle implements Handle {

    private final Connection connection;
    private final int key;
    private boolean closed;

    private SessionHandle(Connection connection, int key) {
      this.connection = connection;
      this.key = key;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        callBoolean(connection, RELEASE_SQL, key);
      } catch (SQLException error) {
        // 显式解锁只是尽快让出；连接关闭本身就会结束会话并释放会话级锁。
        log.warn("failed to release storage upload lock; closing the lock session releases it");
      } finally {
        closeQuietly(connection);
      }
    }
  }

  /** 独占锁句柄：{@link #close()} 幂等，释放锁并关闭持锁会话（会话结束同样释放锁）。 */
  public interface Handle extends AutoCloseable {

    @Override
    void close();
  }
}
