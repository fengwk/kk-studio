package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * J4 转换内核在真实 PostgreSQL 17 上的 schema 与迁移历史拒绝路径：源/目标应用 schema 的任何未列入白名单对象、被改动的 RI trigger 状态，以及
 * 两源与目标的 Flyway 基线历史异常都必须先拒绝。
 *
 * <p>每个用例只对一份真实冻结 DDL 库做一处 DDL 或历史行的合法变异（绝不插数据时绕过约束、绝不关 trigger 插入非法数据），然后断言固定阶段拒绝；源端用例的 目标必须仍是
 * pristine，目标端用例的全部表行数必须与变异前完全一致。
 *
 * <p>包内可见（package-private）：它只是升级测试的 schema 拒绝事实，不是生产代码。
 */
class LegacySchemaRejectionPostgresIntegrationTest {

  private static final SecretKey SOURCE_KEY = key(0x11);

  private static final SecretKey TARGET_KEY = key(0x22);

  /** 单点 schema/历史变异：真实单条 DDL 或历史 SQL，及其必须命中的固定阶段。 */
  private record SchemaDrift(String label, String profile, String stageName, String sql) {

    @Override
    public String toString() {
      return label;
    }
  }

  /** 源 schema 的未知对象与被改动的 RI trigger 状态都必须让源 profile 指纹失配。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceSchemaDrifts")
  void sourceSchemaDriftIsRejected(SchemaDrift drift) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(drift.profile());
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    execute(source, drift.sql());

    Map<String, Long> before = tableCounts(target);
    List<Long> catalogBefore = targetCatalogCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.valueOf(drift.stageName()),
        error.stage(),
        drift.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    UpgradePostgresTestSupport.assertPristine(target);
    assertCounts(target, before);
    assertCatalogCounts(target, catalogBefore);
  }

  static Stream<Arguments> sourceSchemaDrifts() {
    return Stream.of(
        drift("unknown application table", "dev", "SOURCE_PROFILE", unknownTable()),
        drift(
            "unknown table whose name only shares the flyway history prefix",
            "dev",
            "SOURCE_PROFILE",
            "create table flyway_schema_history_backup (installed_rank integer primary key,"
                + " version varchar(50), checksum integer, success boolean, type varchar(20))"),
        drift(
            "unknown view",
            "dev",
            "SOURCE_PROFILE",
            "create view unknown_extra_view as select 1 as one"),
        drift(
            "unknown routine",
            "dev",
            "SOURCE_PROFILE",
            "create function unknown_extra_routine() returns integer language sql as 'select 1'"),
        drift(
            "unknown enum type",
            "dev",
            "SOURCE_PROFILE",
            "create type unknown_extra_enum as enum ('A', 'B')"),
        // 数组伴生类型已由 typarray 链接排除；下划线开头的独立用户类型仍须进入指纹。
        drift(
            "underscore-prefixed enum type",
            "dev",
            "SOURCE_PROFILE",
            "create type _unknown_enum as enum ('A', 'B')"),
        drift(
            "underscore-prefixed domain type",
            "dev",
            "SOURCE_PROFILE",
            "create domain _unknown_domain as text"),
        drift(
            "underscore-prefixed composite type on MAIN",
            "main",
            "SOURCE_PROFILE",
            "create type _unknown_composite as (value text)"),
        // 未定义的 shell type（typtype='p'）不是任何类型种类白名单的成员，仍必须是未知 schema 对象。
        drift("shell type", "dev", "SOURCE_PROFILE", shellType()),
        drift("shell type on the MAIN source", "main", "SOURCE_PROFILE", shellType()),
        drift("standalone collation", "dev", "SOURCE_PROFILE", standaloneCollation()),
        drift(
            "standalone collation on the MAIN source",
            "main",
            "SOURCE_PROFILE",
            standaloneCollation()),
        drift(
            "operator over an existing builtin routine",
            "dev",
            "SOURCE_PROFILE",
            builtinOperator()),
        drift(
            "operator over an existing builtin routine on the MAIN source",
            "main",
            "SOURCE_PROFILE",
            builtinOperator()),
        // 只装进 pg_catalog 的 contrib extension：public namespace 完全看不到它。
        drift("extension installed into pg_catalog", "dev", "SOURCE_PROFILE", catalogExtension()),
        drift(
            "extension installed into pg_catalog on the MAIN source",
            "main",
            "SOURCE_PROFILE",
            catalogExtension()),
        drift(
            "unknown sequence", "dev", "SOURCE_PROFILE", "create sequence unknown_extra_sequence"),
        drift("unknown namespace", "dev", "SOURCE_PROFILE", "create schema unknown_extra_schema"),
        drift(
            "extra column",
            "dev",
            "SOURCE_PROFILE",
            "alter table agent_provider add column unknown_extra_column text"),
        drift(
            "extra constraint",
            "dev",
            "SOURCE_PROFILE",
            "alter table agent_provider add constraint ck_unknown_extra check (name <> 'forbidden')"),
        drift(
            "extra index",
            "dev",
            "SOURCE_PROFILE",
            "create index idx_unknown_extra on agent_provider (version)"),
        // 真实 RI trigger 状态改动：只改状态后拒绝，绝不在这种状态下插入数据。
        drift(
            "disabled foreign key trigger",
            "dev",
            "SOURCE_PROFILE",
            "alter table agent_model disable trigger all"),
        drift(
            "replica foreign key trigger",
            "dev",
            "SOURCE_PROFILE",
            replicaRiTrigger("agent_model")),
        drift(
            "unknown application table on the MAIN source",
            "main",
            "SOURCE_PROFILE",
            unknownTable()));
  }

  /** 两源与目标的基线历史都必须是唯一成功的 versioned V1 且校验和匹配。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("historyDrifts")
  void historyDriftIsRejected(SchemaDrift drift) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(drift.profile());
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    if ("TARGET_SCHEMA".equals(drift.stageName())) {
      execute(target, drift.sql());
    } else {
      execute(source, drift.sql());
    }

    Map<String, Long> before = tableCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.valueOf(drift.stageName()),
        error.stage(),
        drift.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    assertCounts(target, before);
  }

  static Stream<Arguments> historyDrifts() {
    String devChecksum = Long.toString(LegacySchemaProfile.DEV_V1_CHECKSUM);
    return Stream.of(
        drift(
            "DEV baseline checksum mismatch",
            "dev",
            "SOURCE_HISTORY",
            "update flyway_schema_history set checksum = "
                + devChecksum
                + " + 1 where version = '1'"),
        drift(
            "MAIN baseline checksum mismatch",
            "main",
            "SOURCE_HISTORY",
            "update flyway_schema_history set checksum = "
                + LegacySchemaProfile.MAIN_V1_CHECKSUM
                + " + 1 where version = '1'"),
        drift(
            "failed source migration record",
            "dev",
            "SOURCE_HISTORY",
            "update flyway_schema_history set success = false where version = '1'"),
        drift(
            "unknown source migration type",
            "dev",
            "SOURCE_HISTORY",
            "update flyway_schema_history set type = 'JDBC' where version = '1'"),
        drift(
            "non-additive source migration record",
            "dev",
            "SOURCE_HISTORY",
            "insert into flyway_schema_history (installed_rank, version, description, type, script,"
                + " checksum, installed_by, installed_on, execution_time, success)"
                + " select 2, '1', 'undo', 'UNDO', 'U1__undo.sql', 1, installed_by, installed_on,"
                + " 0, true from flyway_schema_history where installed_rank = 1"),
        drift(
            "future versioned source migration",
            "dev",
            "SOURCE_HISTORY",
            "insert into flyway_schema_history (installed_rank, version, description, type, script,"
                + " checksum, installed_by, installed_on, execution_time, success)"
                + " select 2, '2', 'future', 'SQL', 'V2__future.sql', 1, installed_by,"
                + " installed_on, 0, true from flyway_schema_history where installed_rank = 1"),
        drift(
            "missing source baseline history",
            "dev",
            "SOURCE_HISTORY",
            "delete from flyway_schema_history"),
        drift(
            "target baseline checksum mismatch",
            "dev",
            "TARGET_SCHEMA",
            "update flyway_schema_history set checksum = "
                + LegacySchemaProfile.TARGET_V1_CHECKSUM
                + " + 1 where version = '1'"),
        drift(
            "failed target migration record",
            "dev",
            "TARGET_SCHEMA",
            "update flyway_schema_history set success = false where version = '1'"),
        drift(
            "future versioned target migration",
            "dev",
            "TARGET_SCHEMA",
            "insert into flyway_schema_history (installed_rank, version, description, type, script,"
                + " checksum, installed_by, installed_on, execution_time, success)"
                + " select 2, '2', 'future', 'SQL', 'V2__future.sql', 1, installed_by,"
                + " installed_on, 0, true from flyway_schema_history where installed_rank = 1"),
        drift(
            "missing target baseline history",
            "dev",
            "TARGET_SCHEMA",
            "delete from flyway_schema_history"));
  }

  /** 目标应用 schema 的任何未列入白名单对象都让目标指纹失配，且不会改写任何业务行或 schema 对象。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("targetSchemaDrifts")
  void targetSchemaDriftIsRejected(SchemaDrift drift) throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase(drift.profile());
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    execute(target, drift.sql());

    Map<String, Long> before = tableCounts(target);
    List<Long> catalogBefore = targetCatalogCounts(target);
    LegacyDatabaseUpgradeException error = reject(source, target);

    assertEquals(
        LegacyDatabaseUpgradeException.Stage.valueOf(drift.stageName()),
        error.stage(),
        drift.label());
    assertFalse(error.outcomeUncertain());
    assertNull(error.getCause());
    assertCounts(target, before);
    assertCatalogCounts(target, catalogBefore);
  }

  static Stream<Arguments> targetSchemaDrifts() {
    return Stream.of(
        drift("unknown target table", "dev", "TARGET_SCHEMA", unknownTable()),
        drift(
            "unknown target table sharing the flyway history prefix",
            "dev",
            "TARGET_SCHEMA",
            "create table flyway_schema_history_backup (installed_rank integer primary key,"
                + " version varchar(50), checksum integer, success boolean, type varchar(20))"),
        drift(
            "unknown target view",
            "dev",
            "TARGET_SCHEMA",
            "create view unknown_extra_view as select 1 as one"),
        drift(
            "underscore-prefixed target enum",
            "dev",
            "TARGET_SCHEMA",
            "create type _unknown_enum as enum ('A', 'B')"),
        drift(
            "underscore-prefixed target domain",
            "dev",
            "TARGET_SCHEMA",
            "create domain _unknown_domain as text"),
        drift(
            "underscore-prefixed target composite",
            "dev",
            "TARGET_SCHEMA",
            "create type _unknown_composite as (value text)"),
        drift("target shell type", "dev", "TARGET_SCHEMA", shellType()),
        drift("target standalone collation", "dev", "TARGET_SCHEMA", standaloneCollation()),
        drift(
            "target operator over an existing builtin routine",
            "dev",
            "TARGET_SCHEMA",
            builtinOperator()),
        drift(
            "target extension installed into pg_catalog",
            "dev",
            "TARGET_SCHEMA",
            catalogExtension()),
        drift(
            "extra target column",
            "dev",
            "TARGET_SCHEMA",
            "alter table agent_provider add column unknown_extra_column text"),
        drift(
            "extra target constraint",
            "dev",
            "TARGET_SCHEMA",
            "alter table agent_provider add constraint ck_unknown_extra check (name <> 'forbidden')"),
        drift(
            "disabled target foreign key trigger",
            "dev",
            "TARGET_SCHEMA",
            "alter table agent_model disable trigger all"),
        drift(
            "replica target foreign key trigger",
            "dev",
            "TARGET_SCHEMA",
            replicaRiTrigger("agent_model")));
  }

  /** 真实 Harness 历史 fixture 之上的正向对照：未变异的目标必须仍被接受，证明上面的拒绝不是普遍失败。 */
  @Test
  void untouchedBaselineIsAccepted() throws SQLException {
    DataSource source = UpgradePostgresTestSupport.sourceDatabase("dev");
    DataSource target = UpgradePostgresTestSupport.pristineTargetDatabase();
    LegacyUpgradeSourceFixture.load(source, SOURCE_KEY);
    LegacyHarnessHistoryFixture.insert(source);

    LegacyDatabaseUpgradeResult result =
        LegacyDatabaseUpgrade.convert(
            source,
            target,
            SOURCE_KEY,
            TARGET_KEY,
            LegacyUpgradeSourceFixture.staticSecrets(),
            true,
            LegacyUpgradeSourceFixture.CLOCK);

    assertFalse(result.applied());
    assertEquals("dev", result.sourceProfile());
    UpgradePostgresTestSupport.assertPristine(target);
  }

  private static String unknownTable() {
    return "create table unknown_extra_business (id uuid primary key, note text not null)";
  }

  /** 未定义的 shell type：`create type x;` 只产生 typtype='p' 的类型，不属于任何类型种类白名单。 */
  private static String shellType() {
    return "create type unknown_extra_shell";
  }

  /** 独立 collation：没有任何既有关系/类型/routine 指纹覆盖它。 */
  private static String standaloneCollation() {
    return "create collation unknown_extra_collation (locale = 'C')";
  }

  /** 只引用现有 builtin routine 的 operator：routine 指纹保持完全不变，新对象只有 operator 自身。 */
  private static String builtinOperator() {
    return "create operator === (leftarg = int4, rightarg = int4, function = int4eq)";
  }

  /**
   * 只装进 {@code pg_catalog} 的 contrib extension（PG17 官方镜像自带 postgres_fdw）：extension 自身与它的对象都不在
   * public namespace，因此只有按 extension 目录而不是按 public 对象枚举才能发现。
   */
  private static String catalogExtension() {
    return "create extension postgres_fdw with schema pg_catalog";
  }

  /**
   * 把一张真实表上某个内部 RI trigger 置为 Replica 状态：只改这一个真实外部键约束触发器的状态，绝不插数据、不动其它触发器，也不把动态 trigger 名写进任何 指纹。
   */
  private static String replicaRiTrigger(String table) {
    return "do $$\ndeclare trigger_name text;\nbegin\n"
        + "  select t.tgname into trigger_name from pg_trigger t"
        + " join pg_class c on c.oid = t.tgrelid"
        + " where c.relname = '"
        + table
        + "' and t.tgisinternal order by t.tgname limit 1;\n"
        + "  execute format('alter table "
        + table
        + " enable replica trigger %I', trigger_name);\n"
        + "end $$";
  }

  private static Arguments drift(String label, String profile, String stageName, String sql) {
    return Arguments.of(new SchemaDrift(label, profile, stageName, sql));
  }

  private static LegacyDatabaseUpgradeException reject(DataSource source, DataSource target) {
    return assertThrows(
        LegacyDatabaseUpgradeException.class,
        () ->
            LegacyDatabaseUpgrade.convert(
                source,
                target,
                SOURCE_KEY,
                TARGET_KEY,
                LegacyUpgradeSourceFixture.staticSecrets(),
                false,
                LegacyUpgradeSourceFixture.CLOCK));
  }

  private static void execute(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Map<String, Long> tableCounts(DataSource dataSource) throws SQLException {
    List<String> tables = new ArrayList<>(LegacySchemaProfile.SHARED_TABLES);
    tables.addAll(LegacySchemaProfile.TARGET_ONLY_TABLES);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : tables) {
      counts.put(table, UpgradePostgresTestSupport.count(dataSource, table));
    }
    return counts;
  }

  private static void assertCounts(DataSource dataSource, Map<String, Long> expected)
      throws SQLException {
    for (Map.Entry<String, Long> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          UpgradePostgresTestSupport.count(dataSource, entry.getKey()),
          entry.getKey());
    }
  }

  /**
   * 目标「无写入」的对照，但只覆盖列出的类别：public 内关系、类型、routine、public namespace 依赖与真实迁移历史行数。
   *
   * <p>它能证明拒绝没有追加这些类别的对象或迁移记录，**不等价于「schema 完全未变」**：其它类别的对象（例如仅落在 pg_catalog 的
   * extension、operator、collation）不在其中，原地改写也可能让每个计数保持不变。
   */
  private static List<Long> targetCatalogCounts(DataSource target) throws SQLException {
    return List.of(
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = 'public'"),
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from pg_type t join pg_namespace n on n.oid = t.typnamespace"
                + " where n.nspname = 'public'"),
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
                + " where n.nspname = 'public'"),
        UpgradePostgresTestSupport.longValue(
            target,
            "select count(*) from pg_depend d join pg_namespace n on n.oid = d.refobjid"
                + " where d.refclassid = 'pg_namespace'::regclass and n.nspname = 'public'"),
        UpgradePostgresTestSupport.count(target, "flyway_schema_history"));
  }

  private static void assertCatalogCounts(DataSource target, List<Long> expected)
      throws SQLException {
    assertEquals(
        expected, targetCatalogCounts(target), "the listed target catalog counts must match");
  }

  private static SecretKey key(int fill) {
    byte[] material = new byte[32];
    Arrays.fill(material, (byte) fill);
    return new SecretKeySpec(material, "AES");
  }
}
