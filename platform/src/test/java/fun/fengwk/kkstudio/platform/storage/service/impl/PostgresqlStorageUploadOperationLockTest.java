package fun.fengwk.kkstudio.platform.storage.service.impl;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.storage.error.StorageConflictException;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadOperationLock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * {@link PostgresqlStorageUploadOperationLock} 的真实 PostgreSQL 会话锁语义测试。
 *
 * <p>不需要 Spring 上下文：直接连接共享测试容器，只验证锁本身的独占性、释放与泄漏语义、有界阻塞以及 UUID -> key 映射稳定性。锁的作用域是会话，因此这里 每次 acquire
 * 都是一条独立物理连接，与生产实现完全一致。
 */
class PostgresqlStorageUploadOperationLockTest {

  private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(5);

  /** 记录开出的全部会话：任何失败路径都不允许遗留 DriverManager 连接。 */
  private final List<Connection> opened = new CopyOnWriteArrayList<>();

  private final PostgresqlStorageUploadOperationLock lock =
      new PostgresqlStorageUploadOperationLock(this::openConnection, ACQUIRE_TIMEOUT);

  /** 测试意图：同一 upload 的锁是独占的；close 后必须能重新获取，其它 upload（不同 key）不受影响，且不遗留会话。 */
  @Test
  void lockIsExclusivePerUploadAndReleasedOnClose() {
    UUID uploadId = UUID.randomUUID();
    StorageUploadOperationLock.Handle held = lock.tryAcquire(uploadId);
    assertNotNull(held, "a free upload key must be acquired");
    try {
      assertNull(lock.tryAcquire(uploadId), "the same upload must be mutually exclusive");
      try (StorageUploadOperationLock.Handle other = lock.tryAcquire(differentKey(uploadId))) {
        assertNotNull(other, "a different upload key must not be blocked");
      }
    } finally {
      held.close();
    }
    try (StorageUploadOperationLock.Handle again = lock.tryAcquire(uploadId)) {
      assertNotNull(again, "close must release the session lock");
    }
    assertAllSessionsClosed();
  }

  /** 测试意图：close 幂等；锁的释放不依赖调用方恰好解锁一次。 */
  @Test
  void closeIsIdempotent() {
    UUID uploadId = UUID.randomUUID();
    StorageUploadOperationLock.Handle handle = lock.acquire(uploadId);
    handle.close();
    handle.close();
    try (StorageUploadOperationLock.Handle again = lock.tryAcquire(uploadId)) {
      assertNotNull(again);
    }
    assertAllSessionsClosed();
  }

  /** 测试意图：阻塞取锁必须有界 —— 被占用的锁在会话局部 {@code statement_timeout} 到点后以「上传忙」失败，绝不让请求线程无限挂起，且失败会话被关闭。 */
  @Test
  void blockedAcquireFailsBoundedInsteadOfHanging() {
    UUID uploadId = UUID.randomUUID();
    PostgresqlStorageUploadOperationLock shortTimeoutLock =
        new PostgresqlStorageUploadOperationLock(this::openConnection, Duration.ofMillis(400));
    try (StorageUploadOperationLock.Handle held = lock.acquire(uploadId)) {
      long startedAt = System.nanoTime();
      assertThrows(StorageConflictException.class, () -> shortTimeoutLock.acquire(uploadId));
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
      assertTrue(elapsedMillis >= 300L, "the acquisition must actually wait: " + elapsedMillis);
      assertTrue(
          elapsedMillis < 5_000L, "the blocked acquisition must fail fast: " + elapsedMillis);
    }
    assertAllSessionsClosed();
  }

  /** 测试意图：连接来源失败必须确定性上报为锁基础设施失败，而不是静默降级为「无锁」。 */
  @Test
  void connectionOpenFailureIsReportedWithoutLeaking() {
    PostgresqlStorageUploadOperationLock brokenLock =
        new PostgresqlStorageUploadOperationLock(
            () -> {
              throw new SQLException("lock connection unavailable");
            },
            ACQUIRE_TIMEOUT);
    UUID uploadId = UUID.randomUUID();
    assertThrows(IllegalStateException.class, () -> brokenLock.acquire(uploadId));
    assertThrows(IllegalStateException.class, () -> brokenLock.tryAcquire(uploadId));
  }

  /**
   * 测试意图：UUID -> key 必须稳定（跨进程/跨版本一致），否则滚动发布期间不同节点会落到不同的锁上；namespace 固定且与 Harness 的 Join 准入锁（双 int
   * 键空间的另一个使用者）不同，避免跨子域在同一把 advisory 锁上意外串行。
   */
  @Test
  void advisoryKeyIsStableAndNamespaced() {
    assertEquals(0x53544C4B, PostgresqlStorageUploadOperationLock.NAMESPACE);
    UUID fixed = UUID.fromString("00000000-1111-2222-3333-444444444444");
    assertEquals(1_431_660_134, PostgresqlStorageUploadOperationLock.advisoryKey(fixed));
    assertEquals(
        PostgresqlStorageUploadOperationLock.advisoryKey(fixed),
        PostgresqlStorageUploadOperationLock.advisoryKey(UUID.fromString(fixed.toString())));
    assertNotEquals(
        PostgresqlStorageUploadOperationLock.advisoryKey(fixed),
        PostgresqlStorageUploadOperationLock.advisoryKey(
            UUID.fromString("00000000-1111-2222-3333-444444444445")));
  }

  /** 生成一个映射到不同 key 的 upload id，避免把「碰巧哈希碰撞」误当成锁失效。 */
  private static UUID differentKey(UUID uploadId) {
    int key = PostgresqlStorageUploadOperationLock.advisoryKey(uploadId);
    UUID candidate = UUID.randomUUID();
    while (PostgresqlStorageUploadOperationLock.advisoryKey(candidate) == key) {
      candidate = UUID.randomUUID();
    }
    return candidate;
  }

  /** 测试意图：取锁预算必须为正，非法配置在构造期确定性失败，而不是运行期静默无限等待。 */
  @Test
  void rejectsInvalidConstructionArguments() {
    assertThrows(
        NullPointerException.class,
        () -> new PostgresqlStorageUploadOperationLock(null, ACQUIRE_TIMEOUT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PostgresqlStorageUploadOperationLock(this::openConnection, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlStorageUploadOperationLock(this::openConnection, Duration.ofMillis(-1)));
  }

  /** 测试意图：会话上的 SQL 失败必须上报为锁基础设施失败，且失败的会话立刻关闭，不遗留 DriverManager 连接。 */
  @Test
  void statementFailureIsReportedAndSessionClosed() throws SQLException {
    Connection real = openConnection();
    Connection failing =
        intercept(real, "prepareStatement", new SQLException("lock statement failed"));
    PostgresqlStorageUploadOperationLock failingLock =
        new PostgresqlStorageUploadOperationLock(() -> failing, ACQUIRE_TIMEOUT);

    assertThrows(IllegalStateException.class, () -> failingLock.tryAcquire(UUID.randomUUID()));
    assertTrue(real.isClosed(), "a failed acquisition must close its dedicated session");
  }

  /** 测试意图：驱动/连接错误（非 SQLException）原样上抛，且同样关闭会话。 */
  @Test
  void runtimeFailureDuringAcquisitionIsPropagatedAndSessionClosed() throws SQLException {
    Connection real = openConnection();
    Connection failing =
        intercept(real, "setAutoCommit", new IllegalStateException("driver exploded"));
    PostgresqlStorageUploadOperationLock failingLock =
        new PostgresqlStorageUploadOperationLock(() -> failing, ACQUIRE_TIMEOUT);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> failingLock.acquire(UUID.randomUUID()));
    assertEquals("driver exploded", error.getMessage());
    assertTrue(real.isClosed(), "a failed acquisition must close its dedicated session");
  }

  /** 测试意图：关闭会话失败必须被吞掉（锁随会话结束释放），不能把清理异常暴露给调用方。 */
  @Test
  void closeFailureIsTolerated() throws SQLException {
    Connection real = openConnection();
    Connection failingClose = intercept(real, "close", new SQLException("close failed"));
    PostgresqlStorageUploadOperationLock failingLock =
        new PostgresqlStorageUploadOperationLock(() -> failingClose, ACQUIRE_TIMEOUT);

    StorageUploadOperationLock.Handle handle = failingLock.tryAcquire(UUID.randomUUID());
    assertNotNull(handle);
    assertDoesNotThrow(handle::close, "a failing session close must not surface to the caller");
    assertFalse(real.isClosed(), "the intercepted session close failed as intended");
    real.close();
  }

  /** 测试意图：显式解锁失败也必须关掉会话 —— 会话结束释放锁是最后一道保证。 */
  @Test
  void unlockFailureStillReleasesTheLockByClosingTheSession() throws SQLException {
    UUID uploadId = UUID.randomUUID();
    Connection real = openConnection();
    Connection unlockFailing = interceptUnlock(real, new SQLException("unlock failed"));
    PostgresqlStorageUploadOperationLock failingLock =
        new PostgresqlStorageUploadOperationLock(() -> unlockFailing, ACQUIRE_TIMEOUT);

    try (StorageUploadOperationLock.Handle handle = failingLock.tryAcquire(uploadId)) {
      assertNotNull(handle);
      assertNull(lock.tryAcquire(uploadId), "the lock must be held by the live session");
    }
    assertTrue(real.isClosed());
    try (StorageUploadOperationLock.Handle again = lock.tryAcquire(uploadId)) {
      assertNotNull(again, "closing the session must release the lock");
    }
  }

  /** 把真实会话的单个方法替换为确定性失败，其余调用透传；用于验证失败路径的资源清理。 */
  private static Connection intercept(Connection real, String methodName, Throwable thrown) {
    return proxy(real, (called, args) -> methodName.equals(called.getName()), thrown);
  }

  /** 只让显式解锁语句失败，其余语句（含 try-lock）透传。 */
  private static Connection interceptUnlock(Connection real, Throwable thrown) {
    return proxy(
        real,
        (called, args) ->
            "prepareStatement".equals(called.getName())
                && args != null
                && args.length > 0
                && args[0] instanceof String sql
                && sql.contains("advisory_unlock"),
        thrown);
  }

  private static Connection proxy(
      Connection real, BiPredicate<Method, Object[]> fails, Throwable thrown) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, called, args) -> {
              if (fails.test(called, args)) {
                throw thrown;
              }
              return switch (called.getName()) {
                case "toString" -> "intercepted connection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> {
                  try {
                    yield called.invoke(real, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                }
              };
            });
  }

  private void assertAllSessionsClosed() {
    assertTrue(
        opened.stream().allMatch(PostgresqlStorageUploadOperationLockTest::isClosed),
        opened::toString);
  }

  private static boolean isClosed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException error) {
      return false;
    }
  }

  private Connection openConnection() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    opened.add(connection);
    return connection;
  }
}
