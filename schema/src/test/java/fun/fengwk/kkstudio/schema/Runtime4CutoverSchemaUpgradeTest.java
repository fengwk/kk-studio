package fun.fengwk.kkstudio.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 3.0.0 -> 4.0.0 一次性离线 DDL 的验收：测试自己启动隔离 PostgreSQL，用 Flyway 真实应用旧 V1 资源，再以 operator 的 psql 路径执行
 * {@code scripts/ops/runtime4-cutover.sql}。
 *
 * <p>覆盖四类不可退化的证据：固定 guard checksum 来自 Flyway 对旧 V1 的真实计算；成功路径保留真实业务表与 退出行并安装 OWNER_EXITED 约束；未知/已升级
 * baseline、活动会话与重复执行全部默认拒绝且不修改数据库； 显式 Flyway repair 只在隔离库恢复 checksum 校验。
 */
class Runtime4CutoverSchemaUpgradeTest {

  private static final String POSTGRES_IMAGE = "postgres:17-alpine";
  private static final String DATABASE = "kkstudio_runtime4_cutover";
  private static final String USERNAME = "kkstudio_cutover";
  private static final String PASSWORD = "kkstudio_cutover";
  private static final String OLD_MIGRATION_LOCATION =
      "classpath:fun/fengwk/kkstudio/schema/runtime4-cutover/migration";
  private static final String CURRENT_MIGRATION_LOCATION = "classpath:db/migration";
  private static final String CUTOVER_SCRIPT_RESOURCE = "scripts/ops/runtime4-cutover.sql";
  private static final String CONTAINER_CUTOVER_SCRIPT = "/tmp/runtime4-cutover.sql";
  private static final String NEW_STATE_COMMENT =
      "退出证据：未确认、Studio 退出、本地所有者退出、远端 ACK、确定未启动，或用户显式放弃（ABANDONED 不代表物理退出）";
  private static final Pattern BASELINE_CHECKSUM =
      Pattern.compile("(?m)^\\s*baseline_checksum\\s+constant\\s+integer\\s*:=\\s*(-?\\d+);\\s*$");

  private static final UUID ENVIRONMENT_ID = uuid(1000);
  private static final UUID SESSION_ID = uuid(1100);
  private static final UUID ROOT_ENTRY_ID = uuid(1101);
  private static final UUID THREAD_ID = uuid(1102);
  private static final UUID DRIVER_EXIT_ID = uuid(1200);
  private static final UUID TOOL_EXIT_ID = uuid(1201);
  private static final UUID REMOTE_EXIT_ID = uuid(1202);
  private static final UUID ABANDONED_EXIT_ID = uuid(1203);
  private static final UUID STORAGE_BLOB_ID = uuid(1300);
  private static final UUID CANVAS_ID = uuid(1301);
  private static final UUID PROJECT_ID = uuid(1302);
  private static final UUID CHAT_ID = uuid(1303);
  private static final String REGISTERED_AT = "'2026-10-23 10:00:00.123+00'";

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path CUTOVER_SCRIPT = REPOSITORY_ROOT.resolve(CUTOVER_SCRIPT_RESOURCE);
  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName(DATABASE)
          .withUsername(USERNAME)
          .withPassword(PASSWORD);

  @BeforeAll
  static void startIsolatedPostgres() {
    POSTGRES.start();
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(CUTOVER_SCRIPT), CONTAINER_CUTOVER_SCRIPT);
  }

  @AfterAll
  static void stopIsolatedPostgres() {
    POSTGRES.close();
  }

  @Test
  void guardChecksumIsTheRealFlywayChecksumOfThe3_0_0Baseline() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);

    Flyway flyway = flyway(database, OLD_MIGRATION_LOCATION);
    MigrationInfo current = flyway.info().current();
    assertNotNull(current);
    assertNotNull(current.getChecksum());
    assertEquals(current.getChecksum(), historyChecksum(database));
    assertEquals(current.getChecksum().intValue(), guardChecksum());
  }

  @Test
  void cutoverPreservesRowsAndInstallsTheOwnerExitedContract() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    String exitRowsBefore = snapshotRows(database, "harness_execution_exit", "execution_id");
    String businessRowsBefore = businessSnapshot(database);
    int historyChecksumBefore = historyChecksum(database);

    ExecResult result = runCutover(database);
    assertEquals(0, result.getExitCode(), () -> result.getStdout() + result.getStderr());

    assertEquals(1, historyCount(database));
    assertEquals(historyChecksumBefore, historyChecksum(database));
    assertEquals(exitRowsBefore, snapshotRows(database, "harness_execution_exit", "execution_id"));
    assertEquals(businessRowsBefore, businessSnapshot(database));
    assertTrue(hasConstraint(database, "ck_harness_execution_exit_owner_exited"));
    assertTrue(
        constraintDefinition(database, "ck_harness_execution_exit_state").contains("OWNER_EXITED"));
    assertEquals(NEW_STATE_COMMENT, stateComment(database));

    execute(
        database,
        """
        insert into harness_execution_exit (
            execution_id, thread_id, invocation_id, attempt, owner_lease_token, kind,
            parent_execution_id, environment_id, state, registered_at, confirmed_at
        ) values
            ('%s', '%s', '%s', 1, '%s', 'DRIVER', null, null,
                'OWNER_EXITED', %s, %s::timestamptz + interval '1 millisecond'),
            ('%s', '%s', '%s', 1, '%s', 'TOOL', null, null,
                'OWNER_EXITED', %s, %s::timestamptz + interval '1 millisecond')
        """
            .formatted(
                uuid(1400),
                THREAD_ID,
                uuid(1401),
                uuid(1402),
                REGISTERED_AT,
                REGISTERED_AT,
                uuid(1403),
                THREAD_ID,
                uuid(1404),
                uuid(1405),
                REGISTERED_AT,
                REGISTERED_AT));

    SQLException rejected =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    database,
                    """
                    update harness_execution_exit
                    set state = 'OWNER_EXITED',
                        confirmed_at = registered_at + interval '1 millisecond'
                    where execution_id = '%s'
                    """
                        .formatted(REMOTE_EXIT_ID)));
    assertEquals("23514", rejected.getSQLState());
    assertTrue(rejected.getMessage().contains("ck_harness_execution_exit_owner_exited"));
    assertEquals("UNCONFIRMED", queryString(database, stateOf(REMOTE_EXIT_ID)));
  }

  @Test
  void fixedSearchPathOnlyTouchesPublicTables() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    execute(
        database,
        "create schema shadow",
        "create table shadow.flyway_schema_history "
            + "(like public.flyway_schema_history including all)",
        "insert into shadow.flyway_schema_history select * from public.flyway_schema_history",
        "create table shadow.harness_execution_exit "
            + "(like public.harness_execution_exit including all)",
        """
        insert into shadow.harness_execution_exit (
            execution_id, thread_id, invocation_id, attempt, owner_lease_token, kind,
            state, registered_at
        ) values ('%s', '%s', '%s', 1, '%s', 'TOOL', 'UNCONFIRMED', %s)
        """
            .formatted(uuid(1500), THREAD_ID, uuid(1501), uuid(1502), REGISTERED_AT),
        "comment on column shadow.harness_execution_exit.state is 'shadow sentinel comment'");
    int historyChecksumBefore = historyChecksum(database);
    String publicExitRowsBefore = snapshotRows(database, "harness_execution_exit", "execution_id");
    String publicBusinessRowsBefore = businessSnapshot(database);
    String shadowHistoryBefore = snapshotRows(database, "shadow.flyway_schema_history", "version");
    String shadowExitRowsBefore =
        snapshotRows(database, "shadow.harness_execution_exit", "execution_id");
    String shadowStateDefinitionBefore =
        constraintDefinitionIn(
            database, "shadow.harness_execution_exit", "ck_harness_execution_exit_state");
    assertFalse(shadowStateDefinitionBefore.contains("OWNER_EXITED"));

    // 启动时 search_path 先指向 shadow；脚本必须在事务内固定回 pg_catalog, public。
    ExecResult result = runCutover(database, "shadow,public");
    assertEquals(0, result.getExitCode(), () -> result.getStdout() + result.getStderr());

    assertTrue(hasConstraint(database, "ck_harness_execution_exit_owner_exited"));
    assertFalse(
        hasConstraintIn(
            database, "shadow.harness_execution_exit", "ck_harness_execution_exit_owner_exited"));
    assertTrue(
        constraintDefinition(database, "ck_harness_execution_exit_state").contains("OWNER_EXITED"));
    assertEquals(
        shadowStateDefinitionBefore,
        constraintDefinitionIn(
            database, "shadow.harness_execution_exit", "ck_harness_execution_exit_state"));
    assertEquals(NEW_STATE_COMMENT, stateComment(database));
    assertEquals(
        "shadow sentinel comment",
        columnComment(database, "shadow.harness_execution_exit", "state"));
    assertEquals(historyChecksumBefore, historyChecksum(database));
    assertEquals(
        publicExitRowsBefore, snapshotRows(database, "harness_execution_exit", "execution_id"));
    assertEquals(publicBusinessRowsBefore, businessSnapshot(database));
    assertEquals(
        shadowHistoryBefore, snapshotRows(database, "shadow.flyway_schema_history", "version"));
    assertEquals(
        shadowExitRowsBefore,
        snapshotRows(database, "shadow.harness_execution_exit", "execution_id"));
  }

  @Test
  void databaseWithoutFlywayBaselineIsRefused() throws Exception {
    String database = createDatabase();

    ExecResult result = runCutover(database);
    assertNotEquals(0, result.getExitCode());
    assertTrue(result.getStderr().contains("flyway_schema_history is missing"), result.getStderr());
  }

  @Test
  void unknownChecksumIsRefusedWithoutRepairOrSchemaChange() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    execute(database, "update flyway_schema_history set checksum = checksum + 1");
    int unknownChecksum = historyChecksum(database);
    String exitRowsBefore = snapshotRows(database, "harness_execution_exit", "execution_id");

    ExecResult result = runCutover(database);
    assertNotEquals(0, result.getExitCode());
    assertTrue(result.getStderr().contains("checksum"), result.getStderr());

    assertEquals(1, historyCount(database));
    assertEquals(unknownChecksum, historyChecksum(database));
    assertFalse(hasConstraint(database, "ck_harness_execution_exit_owner_exited"));
    assertEquals(exitRowsBefore, snapshotRows(database, "harness_execution_exit", "execution_id"));
  }

  @Test
  void alreadyCutOverBaselineIsRefusedOnRepeatRun() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    assertEquals(0, runCutover(database).getExitCode());
    int historyChecksumBeforeRepeat = historyChecksum(database);
    String exitRowsBeforeRepeat = snapshotRows(database, "harness_execution_exit", "execution_id");
    String businessRowsBeforeRepeat = businessSnapshot(database);

    ExecResult repeat = runCutover(database);
    assertNotEquals(0, repeat.getExitCode());
    assertTrue(repeat.getStderr().contains("already exists"), repeat.getStderr());

    assertEquals(1, historyCount(database));
    assertEquals(historyChecksumBeforeRepeat, historyChecksum(database));
    assertEquals(
        exitRowsBeforeRepeat, snapshotRows(database, "harness_execution_exit", "execution_id"));
    assertEquals(businessRowsBeforeRepeat, businessSnapshot(database));
  }

  @Test
  void current4_0_0BaselineIsRefused() throws Exception {
    String database = createDatabase();
    migrate(database, CURRENT_MIGRATION_LOCATION);
    int currentChecksum = historyChecksum(database);

    ExecResult result = runCutover(database);
    assertNotEquals(0, result.getExitCode());
    assertTrue(result.getStderr().contains("checksum"), result.getStderr());
    assertEquals(1, historyCount(database));
    assertEquals(currentChecksum, historyChecksum(database));
  }

  @Test
  void activeClientSessionIsRefusedAndNeverTerminated() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    int historyChecksumBefore = historyChecksum(database);
    String exitRowsBefore = snapshotRows(database, "harness_execution_exit", "execution_id");

    try (Connection otherSession = connect(database)) {
      try (Statement statement = otherSession.createStatement();
          ResultSet resultSet = statement.executeQuery("select 1")) {
        assertTrue(resultSet.next());
      }

      ExecResult result = runCutover(database);
      assertNotEquals(0, result.getExitCode());
      assertTrue(result.getStderr().contains("other client session"), result.getStderr());
      assertTrue(otherSession.isValid(1), "the cutover script must never terminate sessions");
    }

    assertEquals(historyChecksumBefore, historyChecksum(database));
    assertFalse(hasConstraint(database, "ck_harness_execution_exit_owner_exited"));
    assertEquals(exitRowsBefore, snapshotRows(database, "harness_execution_exit", "execution_id"));
  }

  @Test
  void explicitFlywayRepairRestoresCurrentBaselineValidation() throws Exception {
    String database = createDatabase();
    migrate(database, OLD_MIGRATION_LOCATION);
    insertSentinelRows(database);
    String exitRowsBeforeRepair = snapshotRows(database, "harness_execution_exit", "execution_id");
    String businessRowsBeforeRepair = businessSnapshot(database);
    assertEquals(0, runCutover(database).getExitCode());

    Flyway current = flyway(database, CURRENT_MIGRATION_LOCATION);
    int currentChecksum = currentBaselineChecksum();
    assertEquals(guardChecksum(), historyChecksum(database));
    assertNotEquals(historyChecksum(database), currentChecksum);

    assertThrows(FlywayException.class, current::validate);
    current.repair();
    current.validate();

    assertEquals(currentChecksum, historyChecksum(database));
    assertEquals(1, historyCount(database));
    assertEquals(
        exitRowsBeforeRepair, snapshotRows(database, "harness_execution_exit", "execution_id"));
    assertEquals(businessRowsBeforeRepair, businessSnapshot(database));
  }

  @Test
  void scriptNeverMutatesHistoryOrTerminatesSessions() throws IOException {
    String script = Files.readString(CUTOVER_SCRIPT).toLowerCase(Locale.ROOT);
    assertTrue(script.contains("begin;"));
    assertTrue(script.contains("commit;"));
    for (String forbidden :
        List.of(
            "pg_terminate_backend",
            "pg_cancel_backend",
            "delete from",
            "truncate ",
            "alter table flyway_schema_history",
            "update flyway_schema_history",
            "insert into flyway_schema_history",
            "delete from flyway_schema_history",
            "insert into harness_execution_exit",
            "update harness_execution_exit",
            "delete from harness_execution_exit")) {
      assertFalse(script.contains(forbidden), forbidden);
    }
  }

  private static void insertSentinelRows(String database) throws SQLException {
    execute(
        database,
        """
        insert into environment (id, name, registration_token_hash, daemon_config)
        values ('%s', 'sentinel-environment', repeat('a', 64),
            '{"studioUrl":"https://studio.example.com"}'::jsonb)
        """
            .formatted(ENVIRONMENT_ID),
        """
        insert into storage_blob (id, sha256, size_bytes, media_type, ref_count)
        values ('%s', repeat('c', 64), 12, 'text/plain', 1)
        """
            .formatted(STORAGE_BLOB_ID),
        """
        insert into canvas_document (id, title)
        values ('%s', 'sentinel canvas')
        """
            .formatted(CANVAS_ID),
        """
        insert into project (id, title, workflow)
        values ('%s', 'sentinel project', '{"states":[]}'::jsonb)
        """
            .formatted(PROJECT_ID),
        """
        insert into chat (id, title, agent_name, environment_name)
        values ('%s', 'sentinel chat', 'sentinel-agent', 'sentinel-environment')
        """
            .formatted(CHAT_ID),
        """
        insert into harness_session (id, name, created_at)
        values ('%s', 'sentinel session', %s)
        """
            .formatted(SESSION_ID, REGISTERED_AT),
        """
        insert into harness_entry (id, session_id, entry_type, payload, created_at)
        values ('%s', '%s', 'ROOT', '{}'::jsonb, %s)
        """
            .formatted(ROOT_ENTRY_ID, SESSION_ID, REGISTERED_AT),
        """
        insert into harness_thread (
            id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name,
            root_thread_id, spawn_trigger, constraints, execution_control,
            next_command_sequence, version, created_at, updated_at
        ) values (
            '%s', '%s', null, '%s', repeat('b', 64), 'sentinel root',
            '%s', 'USER', '{}'::jsonb, 'RUNNABLE', 1, 0, %s, %s
        )
        """
            .formatted(
                THREAD_ID, SESSION_ID, ROOT_ENTRY_ID, THREAD_ID, REGISTERED_AT, REGISTERED_AT),
        """
        insert into harness_execution_exit (
            execution_id, thread_id, invocation_id, attempt, owner_lease_token, kind,
            parent_execution_id, environment_id, state, registered_at, confirmed_at,
            abandoned_at
        ) values
            ('%s', '%s', '%s', 1, '%s', 'DRIVER', null, null,
                'STUDIO_EXITED', %s, %s::timestamptz + interval '1 millisecond', null),
            ('%s', '%s', '%s', 1, '%s', 'TOOL', null, null,
                'UNCONFIRMED', %s, null, null),
            ('%s', '%s', '%s', 1, '%s', 'REMOTE', '%s', '%s',
                'UNCONFIRMED', %s, null, null),
            ('%s', '%s', '%s', 1, '%s', 'TOOL', null, null,
                'ABANDONED', %s, null, %s::timestamptz + interval '1 millisecond')
        """
            .formatted(
                DRIVER_EXIT_ID,
                THREAD_ID,
                uuid(1204),
                uuid(1205),
                REGISTERED_AT,
                REGISTERED_AT,
                TOOL_EXIT_ID,
                THREAD_ID,
                uuid(1206),
                uuid(1207),
                REGISTERED_AT,
                REMOTE_EXIT_ID,
                THREAD_ID,
                uuid(1206),
                uuid(1208),
                TOOL_EXIT_ID,
                ENVIRONMENT_ID,
                REGISTERED_AT,
                ABANDONED_EXIT_ID,
                THREAD_ID,
                uuid(1209),
                uuid(1210),
                REGISTERED_AT,
                REGISTERED_AT));
  }

  private static String businessSnapshot(String database) throws SQLException {
    return String.join(
        "\n--\n",
        snapshotRows(database, "storage_blob", "id"),
        snapshotRows(database, "environment", "id"),
        snapshotRows(database, "canvas_document", "id"),
        snapshotRows(database, "project", "id"),
        snapshotRows(database, "chat", "id"),
        snapshotRows(database, "harness_session", "id"),
        snapshotRows(database, "harness_thread", "id"));
  }

  private static String snapshotRows(String database, String table, String orderBy)
      throws SQLException {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select row_to_json(t)::text from " + table + " t order by " + orderBy)) {
      List<String> rows = new ArrayList<>();
      while (resultSet.next()) {
        rows.add(resultSet.getString(1));
      }
      return String.join("\n", rows);
    }
  }

  private static String stateOf(UUID executionId) {
    return "select state from public.harness_execution_exit where execution_id = '"
        + executionId
        + "'";
  }

  private static boolean hasConstraint(String database, String constraintName) throws SQLException {
    return hasConstraintIn(database, "public.harness_execution_exit", constraintName);
  }

  private static boolean hasConstraintIn(String database, String table, String constraintName)
      throws SQLException {
    return "1"
        .equals(
            queryStringOrNull(
                database,
                """
                select '1'
                from pg_constraint
                where conrelid = '%s'::regclass
                    and conname = '%s'
                """
                    .formatted(table, constraintName)));
  }

  private static String constraintDefinition(String database, String constraintName)
      throws SQLException {
    return constraintDefinitionIn(database, "public.harness_execution_exit", constraintName);
  }

  private static String constraintDefinitionIn(String database, String table, String constraintName)
      throws SQLException {
    return queryString(
        database,
        """
        select pg_get_constraintdef(oid)
        from pg_constraint
        where conrelid = '%s'::regclass
            and conname = '%s'
        """
            .formatted(table, constraintName));
  }

  private static String stateComment(String database) throws SQLException {
    return columnComment(database, "public.harness_execution_exit", "state");
  }

  private static String columnComment(String database, String table, String column)
      throws SQLException {
    return queryString(
        database,
        """
        select col_description('%s'::regclass, attnum)
        from pg_attribute
        where attrelid = '%s'::regclass
            and attname = '%s'
        """
            .formatted(table, table, column));
  }

  private static int historyChecksum(String database) throws SQLException {
    return Integer.parseInt(
        queryString(
            database, "select checksum from public.flyway_schema_history where version = '1'"));
  }

  private static int historyCount(String database) throws SQLException {
    return Integer.parseInt(
        queryString(database, "select count(*) from public.flyway_schema_history"));
  }

  private static String queryString(String database, String sql) throws SQLException {
    String value = queryStringOrNull(database, sql);
    assertNotNull(value, () -> "query returned no row: " + sql);
    return value;
  }

  private static String queryStringOrNull(String database, String sql) throws SQLException {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      return resultSet.next() ? resultSet.getString(1) : null;
    }
  }

  private static void execute(String database, String... statements) throws SQLException {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static ExecResult runCutover(String database) throws Exception {
    return runCutover(database, null);
  }

  private static ExecResult runCutover(String database, String searchPath) throws Exception {
    if (searchPath != null) {
      return POSTGRES.execInContainer(
          "env",
          "PGOPTIONS=-c search_path=" + searchPath,
          "psql",
          "-X",
          "-q",
          "-v",
          "ON_ERROR_STOP=1",
          "-U",
          USERNAME,
          "-d",
          database,
          "-f",
          CONTAINER_CUTOVER_SCRIPT);
    }
    return POSTGRES.execInContainer(
        "psql",
        "-X",
        "-q",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        USERNAME,
        "-d",
        database,
        "-f",
        CONTAINER_CUTOVER_SCRIPT);
  }

  private static void migrate(String database, String location) {
    flyway(database, location).migrate();
  }

  private static int currentBaselineChecksum() throws SQLException {
    String referenceDatabase = createDatabase();
    migrate(referenceDatabase, CURRENT_MIGRATION_LOCATION);
    return historyChecksum(referenceDatabase);
  }

  private static Flyway flyway(String database, String location) {
    return Flyway.configure()
        .dataSource(jdbcUrl(database), USERNAME, PASSWORD)
        .locations(location)
        .validateMigrationNaming(true)
        .load();
  }

  private static String createDatabase() throws SQLException {
    String database = "cutover_" + DATABASE_SEQUENCE.incrementAndGet();
    try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute("create database " + database);
    }
    return database;
  }

  private static Connection connect(String database) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(database), USERNAME, PASSWORD);
  }

  private static String jdbcUrl(String database) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + database;
  }

  private static int guardChecksum() throws IOException {
    String script = Files.readString(CUTOVER_SCRIPT);
    Matcher matcher = BASELINE_CHECKSUM.matcher(script);
    assertTrue(matcher.find(), "the cutover script has no fixed baseline checksum");
    return Integer.parseInt(matcher.group(1));
  }

  private static UUID uuid(int value) {
    return new UUID(0L, value);
  }

  private static Path locateRepositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve(CUTOVER_SCRIPT_RESOURCE))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("cannot locate " + CUTOVER_SCRIPT_RESOURCE);
  }
}
