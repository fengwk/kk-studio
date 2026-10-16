package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;

/**
 * 连接取得与释放阶段的纯逻辑回归（Mockito 直接核对真实 JDBC API 的委托）：一端取得失败必须归还已取得的另一端，被拒绝的遗留事务只归还句柄， Error 保持 identity
 * 且不跳过其它资源，原始 SQL 绝不进入安全失败链。
 *
 * <p>这些用例都在连接阶段就失败，因此不会走到凭据、目录装配与任何写库路径；真实 PostgreSQL 上的生命周期故障由集成测试覆盖。
 */
class LegacyDatabaseUpgradeConnectionTest {

  /** 取得目标连接失败时必须释放已经打开的源连接，不能把源连接泄漏给调用方。 */
  @Test
  void targetAcquisitionFailureReleasesTheOpenedSource() throws SQLException {
    Connection source = idleConnection();
    DataSource targetDataSource = mock(DataSource.class);
    when(targetDataSource.getConnection()).thenThrow(new SQLException("unavailable"));

    LegacyDatabaseUpgradeException failure =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> convert(dataSource(source), targetDataSource));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, failure.stage());
    assertFalse(failure.outcomeUncertain());
    verify(source).close();
    verify(source, never()).rollback();
    assertNoRawFailure(failure);
  }

  /** getAutoCommit 探测抛出 Error 时，刚取得、尚未交给外层的句柄仍要归还，另一端也被释放，且 Error 保持 identity。 */
  @Test
  void autoCommitProbeErrorReleasesTheHandleAndKeepsTheErrorIdentity() throws SQLException {
    Connection source = idleConnection();
    Connection target = idleConnection();
    Error fatal = new OutOfMemoryError("auto commit probe failed");
    when(target.getAutoCommit()).thenThrow(fatal);

    Error failure =
        assertThrows(Error.class, () -> convert(dataSource(source), dataSource(target)));

    assertSame(fatal, failure);
    verify(target).close();
    verify(source).close();
  }

  /** 数据源给出仍在事务中的连接时只归还句柄：绝不 rollback/commit 调用方自己的事务，同时释放已打开的源连接。 */
  @Test
  void reservedTransactionIsRejectedWithoutTakingItOver() throws SQLException {
    Connection source = idleConnection();
    Connection reserved = mock(Connection.class);
    when(reserved.getAutoCommit()).thenReturn(false);

    LegacyDatabaseUpgradeException failure =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> convert(dataSource(source), dataSource(reserved)));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, failure.stage());
    assertFalse(failure.outcomeUncertain());
    verify(reserved).close();
    verify(reserved, never()).rollback();
    verify(reserved, never()).commit();
    verify(source).close();
    verify(source, never()).rollback();
    assertNoRawFailure(failure);
  }

  /** 一个连接的关闭抛出 Error 时仍要关闭另一个连接，Error 保持 identity 并提升为主失败，原失败去敏后进入压制链。 */
  @Test
  void closeErrorOnOneConnectionDoesNotSkipTheOther() throws SQLException {
    Connection source = idleConnection();
    Connection target = idleConnection();
    Error fatal = new StackOverflowError("target close failed");
    when(source.createStatement()).thenThrow(new SQLException("session setup failed"));
    doThrow(fatal).when(target).close();

    Error failure =
        assertThrows(Error.class, () -> convert(dataSource(source), dataSource(target)));

    assertSame(fatal, failure);
    verify(target).close();
    verify(source).close();
    assertNoRawFailure(failure);
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        LegacyDatabaseUpgradeException.Stage.CONNECTION,
        assertInstanceOf(LegacyDatabaseUpgradeException.class, failure.getSuppressed()[0]).stage());
  }

  /** 释放期的回滚失败不能跳过真正的 close，也只能以固定安全提示并入原失败，绝不带出原始 SQLException 的 SQL 与 message。 */
  @Test
  void rollbackFailureDuringReleaseDoesNotSkipCloseOrLeakRawSql() throws SQLException {
    Connection source = idleConnection();
    Connection target = idleConnection();
    when(source.createStatement()).thenThrow(new SQLException("session setup failed"));
    // 取得探测时是空闲连接，释放时仍处于未恢复的事务中，且回滚本身失败。
    when(target.getAutoCommit()).thenReturn(true, false);
    doThrow(new SQLException("rollback failed")).when(target).rollback();

    LegacyDatabaseUpgradeException failure =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> convert(dataSource(source), dataSource(target)));

    assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, failure.stage());
    assertFalse(failure.outcomeUncertain());
    verify(target).rollback();
    verify(target).close();
    verify(source).close();
    assertNoRawFailure(failure);
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(
        "an upgrade database connection could not be released",
        failure.getSuppressed()[0].getMessage());
  }

  /** 回滚失败不能遮蔽随后 close 的致命错误，也不能带出原始 SQL 诊断。 */
  @Test
  void rollbackFailureDoesNotHideAFatalCloseFailure() throws SQLException {
    Connection source = idleConnection();
    Connection target = idleConnection();
    Error fatal = new AssertionError("synthetic fatal connection close");
    when(source.createStatement()).thenThrow(new SQLException("session setup failed"));
    when(source.getAutoCommit()).thenReturn(true, false);
    doThrow(new SQLException("private rollback diagnostic")).when(source).rollback();
    doThrow(fatal).when(source).close();

    Error failure =
        assertThrows(Error.class, () -> convert(dataSource(source), dataSource(target)));

    assertSame(fatal, failure);
    verify(source).rollback();
    verify(source).close();
    verify(target).close();
    assertNoRawFailure(failure);
  }

  /** 升级入口只在这些用例的连接阶段就失败，不会进入目录装配或写库路径。 */
  private static void convert(DataSource source, DataSource target) {
    LegacyDatabaseUpgrade.convert(source, target, null, null, Map.of(), true, Clock.systemUTC());
  }

  /** 一个已经通过取得探测的普通连接：autocommit=true 且未关闭。 */
  private static Connection idleConnection() throws SQLException {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(true);
    return connection;
  }

  private static DataSource dataSource(Connection connection) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    return dataSource;
  }

  /** 向外传播的失败链只允许固定安全失败：绝无原始 SQLException/RuntimeException 及其 SQL、message 与 cause。 */
  private static void assertNoRawFailure(Throwable failure) {
    assertNull(failure.getCause());
    for (Throwable suppressed : failure.getSuppressed()) {
      assertInstanceOf(LegacyDatabaseUpgradeException.class, suppressed);
      assertNoRawFailure(suppressed);
    }
  }
}
