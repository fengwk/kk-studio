package fun.fengwk.kkstudio.web.upgrade;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 显式空目标初始化：只把一个操作员新建、专用于本次升级的全新空 PostgreSQL 17 数据库推进到当前唯一 baseline。
 *
 * <p>绝不 {@code CREATE DATABASE}/drop/truncate/reset/baseline/repair，也不对已有 V1 库或任何未知
 * namespace/table/view/sequence/type/routine/extension 做幂等迁移：应用前要求 {@code public}
 * 恰好存在且没有任何用户对象。baseline 由真实 Flyway 以唯一 classpath V1 应用（{@code baselineOnMigrate=false}、{@code
 * cleanDisabled=true}、严格 naming、显式 {@code public} default schema、零日志 sink），随后用当前
 * schema/history/pristine 守卫确认，不启动 Spring auto-config/seed/catalog epoch1。
 *
 * <p>Flyway 自己打开迁移连接并按 default schema 改写 search_path，因此固定 session 前置与服务器形态门禁还会通过一个最小 {@link
 * Callback} 在迁移事务内重新应用（见 {@link TargetSessionCallback}）。
 *
 * <p>失败可能已经留下 Flyway 元数据或 DDL，因此应用后的任何失败都保守标记为需人工核验；本类不自动重试、不删除。
 */
final class LegacyDatabasePrepare {

  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  /** {@code public} 是唯一允许的用户 schema，且必须恰好存在一个。 */
  private static final String PUBLIC_SCHEMA_NAME = "public";

  /**
   * 迁移连接的 session search_path：PostgreSQL 用 search_path 首项作为创建命名空间，而 V1 是未限定 schema 的 DDL，因此首位必须是
   * {@code public}；规范读取顺序（{@code pg_catalog} 前置以拒绝旧源遮蔽系统目录）只适用于转换会话，不能用在这里。
   */
  private static final String MIGRATION_SEARCH_PATH =
      "set local search_path = public, pg_catalog, pg_temp";

  /** 门禁查询：{@code public} schema 的存在性（不允许零个或重复）。 */
  private static final String PUBLIC_SCHEMA =
      "select count(*) from pg_namespace where nspname = 'public'";

  /** 空目标里不允许任何用户关系：表 / 分区表 / 视图 / 物化视图 / 序列 / 外部表。 */
  private static final String USER_RELATIONS =
      "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
          + " where n.nspname = 'public' and c.relkind in ('r','p','v','m','S','f')";

  /** 独立用户类型：enum / domain / range / multirange，以及不承载表的独立 composite。 */
  private static final String USER_TYPES =
      "select count(*) from pg_type t join pg_namespace n on n.oid = t.typnamespace"
          + " left join pg_class c on c.oid = t.typrelid"
          + " where n.nspname = 'public' and t.typtype in ('e','d','c','r','m')"
          + " and (t.typrelid = 0 or c.relkind = 'c')";

  private static final String USER_ROUTINES =
      "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
          + " where n.nspname = 'public'";

  private static final String USER_NAMESPACES =
      "select count(*) from pg_namespace where nspname <> 'public'"
          + " and nspname <> 'information_schema' and nspname not like 'pg\\_%'";

  private static final String USER_EXTENSIONS =
      "select count(*) from pg_extension where extname <> 'plpgsql'";

  private static final List<String> BLANK_CHECKS =
      List.of(USER_RELATIONS, USER_TYPES, USER_ROUTINES, USER_NAMESPACES, USER_EXTENSIONS);

  private LegacyDatabasePrepare() {}

  /**
   * 把一个全新空目标库初始化为当前 baseline，并用 schema/history/pristine 守卫确认。
   *
   * @throws LegacyDatabaseUpgradeException 连接或 session 前置失败（真实核心阶段）
   * @throws LegacyUpgradeCliException 目标非空、baseline 应用失败或应用后核验失败；应用后的失败都标记 outcomeUncertain
   */
  static void prepare(DataSource target) {
    requireBlankTarget(target);
    applyBaseline(target);
    verifyInitializedTarget(target);
  }

  /** 应用前门禁：session 前置（PG17/UTF8/origin/writable）之外，目标必须没有任何用户对象。 */
  private static void requireBlankTarget(DataSource target) {
    try (Connection connection = LegacyDatabaseUpgrade.openIdleConnection(target)) {
      connection.setAutoCommit(false);
      LegacyDatabaseUpgrade.beginTargetSession(connection);
      try (Statement statement = connection.createStatement()) {
        if (count(statement, PUBLIC_SCHEMA) != 1) {
          throw failure("the target database has no canonical public schema", false);
        }
        for (String check : BLANK_CHECKS) {
          if (count(statement, check) != 0) {
            throw failure("the target database is not a blank upgrade target", false);
          }
        }
      }
      connection.rollback();
    } catch (SQLException error) {
      // 应用前没有任何 DDL：读取或关闭失败都不代表已有写入。
      throw failure("the target database could not be inspected", false);
    }
  }

  /** 唯一当前 classpath V1 由真实 Flyway 应用；零日志 sink，绝不 clean/baseline/repair。 */
  private static void applyBaseline(DataSource target) {
    try {
      Flyway.configure()
          .dataSource(target)
          .locations(MIGRATION_LOCATION)
          .defaultSchema(PUBLIC_SCHEMA_NAME)
          .schemas(PUBLIC_SCHEMA_NAME)
          .createSchemas(false)
          .baselineOnMigrate(false)
          .cleanDisabled(true)
          .validateMigrationNaming(true)
          .callbacks(new TargetSessionCallback())
          .loggers()
          .load()
          .migrate();
    } catch (RuntimeException error) {
      throw failure("the baseline migration did not complete", true);
    }
  }

  /**
   * 真实迁移连接上的固定 session 前置与服务器形态门禁：preflight/postcheck 只覆盖本类自己打开的连接，Flyway 会另开物理连接并按 default schema
   * 切换 search_path，因此必须在迁移事务内重新应用。
   *
   * <p>BEFORE_EACH_MIGRATE 与迁移本身同一事务（PostgreSQL 的 V1 全是事务性 DDL），且在 Flyway 完成 schema 切换之后触发；
   * 这里失败只意味着目标可能已留下 Flyway 元数据，因此沿 Flyway 边界收敛为上层的 PREPARE 阶段失败。
   *
   * <p>包内可见（package-private）：它只是离线升级包内部的迁移会话前置，不是平台 API；固定事件、同事务声明与名字由包内测试钉住。
   */
  static final class TargetSessionCallback implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
      return Event.BEFORE_EACH_MIGRATE == event;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
      return true;
    }

    @Override
    public void handle(Event event, Context context) {
      Connection connection = context.getConnection();
      LegacyDatabaseUpgrade.beginTargetSession(connection);
      try (Statement statement = connection.createStatement()) {
        statement.execute(MIGRATION_SEARCH_PATH);
      } catch (SQLException error) {
        throw new LegacyDatabaseUpgradeException(
            LegacyDatabaseUpgradeException.Stage.CONNECTION,
            "cannot establish the target migration session");
      }
    }

    @Override
    public String getCallbackName() {
      return "kk-studio-offline-target-session";
    }
  }

  /** 应用后确认目标恰为当前 V1 schema、唯一成功 V1 历史与 pristine 形态；任何失败都可能已有写入。 */
  private static void verifyInitializedTarget(DataSource target) {
    try (Connection connection = LegacyDatabaseUpgrade.openIdleConnection(target)) {
      connection.setAutoCommit(false);
      LegacyDatabaseUpgrade.beginTargetSession(connection);
      LegacySchemaProfile.requireTarget(connection);
      LegacyDatabaseUpgrade.requirePristineTarget(connection);
      connection.rollback();
    } catch (SQLException | RuntimeException error) {
      throw failure("the initialized target database did not match the current baseline", true);
    }
  }

  private static long count(Statement statement, String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static LegacyUpgradeCliException failure(String reason, boolean outcomeUncertain) {
    return new LegacyUpgradeCliException(
        LegacyUpgradeCliException.Stage.PREPARE, reason, outcomeUncertain);
  }
}
