package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * prepare 的失败分类与迁移连接回调的边界：应用前的读取失败必须确定且只输出固定原因；回调只在 BEFORE_EACH_MIGRATE 的事务内触发、名字固定，且 search_path
 * 前置失败必须收敛为固定 CONNECTION 失败而不是泄漏 SQL 或驱动消息。
 *
 * <p>它只钉真实 Flyway 迁移观察不到的分支（真实迁移路径由 {@code *PostgresIntegrationTest} 覆盖），并且只连接可丢弃的隔离容器库。
 */
class LegacyDatabasePrepareTest {

  /** 与 {@code LegacyDatabasePrepare.MIGRATION_SEARCH_PATH} 逐字一致；一旦漂移，本用例会因为缺少失败而失败，而不是静默通过。 */
  private static final String MIGRATION_SEARCH_PATH =
      "set local search_path = public, pg_catalog, pg_temp";

  /** 应用前读取失败（任何 DDL 之前）：必须分类为确定的 PREPARE 失败，不能标记为 outcomeUncertain。 */
  @Test
  void inspectionFailureBeforeAnyDdlIsReportedAsCertain() throws SQLException {
    DataSource target = uncommittable(UpgradePostgresTestSupport.blankDatabase());
    LegacyUpgradeCliException error =
        assertThrows(LegacyUpgradeCliException.class, () -> LegacyDatabasePrepare.prepare(target));
    assertEquals(LegacyUpgradeCliException.Stage.PREPARE, error.stage());
    assertFalse(error.outcomeUncertain());
    assertEquals("the target database could not be inspected", error.getMessage());
  }

  /** 回调只接管 BEFORE_EACH_MIGRATE，并声明与迁移同事务，名字固定供 Flyway 记录。 */
  @Test
  void callbackOnlyHandlesTheMigrateEventInsideTheMigrationTransaction() {
    LegacyDatabasePrepare.TargetSessionCallback callback =
        new LegacyDatabasePrepare.TargetSessionCallback();
    Context context = mock(Context.class);
    assertTrue(callback.supports(Event.BEFORE_EACH_MIGRATE, context));
    assertFalse(callback.supports(Event.BEFORE_MIGRATE, context));
    assertFalse(callback.supports(Event.AFTER_EACH_MIGRATE, context));
    assertTrue(callback.canHandleInTransaction(Event.BEFORE_EACH_MIGRATE, context));
    assertEquals("kk-studio-offline-target-session", callback.getCallbackName());
  }

  /** 迁移连接上的 search_path 前置失败：固定 CONNECTION 阶段与固定原因，不携带 SQL、schema 或驱动消息。 */
  @Test
  void searchPathFailureOnTheMigrationConnectionIsReportedAsAFixedConnectionFailure()
      throws Exception {
    LegacyDatabasePrepare.TargetSessionCallback callback =
        new LegacyDatabasePrepare.TargetSessionCallback();
    try (Connection connection = UpgradePostgresTestSupport.blankDatabase().getConnection()) {
      connection.setAutoCommit(false);
      Context context = mock(Context.class);
      when(context.getConnection()).thenReturn(searchPathBlocked(connection));

      LegacyDatabaseUpgradeException error =
          assertThrows(
              LegacyDatabaseUpgradeException.class,
              () -> callback.handle(Event.BEFORE_EACH_MIGRATE, context));
      assertEquals(LegacyDatabaseUpgradeException.Stage.CONNECTION, error.stage());
      assertEquals("cannot establish the target migration session", error.getMessage());
      connection.rollback();
    }
  }

  /** 真实连接包装：迁移会话前置本身照常可用，只有 search_path 那一条语句失败。 */
  private static Connection searchPathBlocked(Connection connection) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("createStatement".equals(method.getName())
                  && (args == null || args.length == 0)) {
                return blockedStatement(connection.createStatement());
              }
              return invoke(connection, method, args);
            });
  }

  /** 每条语句单独包装，避免 beginTargetSession 关闭自己的语句后影响回调随后创建的语句。 */
  private static Statement blockedStatement(Statement delegate) {
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
              if ("execute".equals(method.getName())
                  && args != null
                  && args.length == 1
                  && MIGRATION_SEARCH_PATH.equals(args[0])) {
                throw new SQLException("the migration search path is unavailable");
              }
              return invoke(delegate, method, args);
            });
  }

  /** 真实空库上包一层「不能进入迁移事务」的连接，用于钉住应用前读取失败的分类。 */
  private static DataSource uncommittable(DataSource target) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object value = invoke(target, method, args);
              if (value instanceof Connection connection) {
                return Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (handle, handleMethod, handleArgs) -> {
                      if ("setAutoCommit".equals(handleMethod.getName())) {
                        throw new SQLException("the target refuses the migration transaction");
                      }
                      return invoke(connection, handleMethod, handleArgs);
                    });
              }
              return value;
            });
  }

  /** 反射调用并原样抛出被包装的异常，保持驱动抛出的 SQLException 类型。 */
  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }
}
