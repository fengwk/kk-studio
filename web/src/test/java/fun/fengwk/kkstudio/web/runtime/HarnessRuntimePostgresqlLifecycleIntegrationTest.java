package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.web.WebTestApplication;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Web 组合根的 worker 生命周期集成测试（Testcontainers PostgreSQL，{@code workers-enabled=true}）。
 *
 * <p>上下文启动时注册 dispatcher periodic poll 与应用共享 PostgreSQL notification loop。测试分别验证 Work NOTIFY
 * 立即唤醒以及无通知时 periodic poll 兜底，最终都经 dispatcher drain -&gt; claim -&gt; ThreadProcessor quiescent
 * complete 删除 work 行。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = WebTestApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HarnessRuntimePostgresqlLifecycleIntegrationTest {

  private static final UUID SESSION_ID = new UUID(0L, 9_900_000L);
  private static final UUID ROOT_ENTRY_ID = new UUID(0L, 9_900_001L);
  private static final UUID THREAD_ID = new UUID(0L, 9_900_002L);
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_web_runtime_test");

  static {
    POSTGRES.start();
    // 上下文创建期装配 bean 会读取 system_setting 默认行：应用唯一 V1 baseline（schema 模块的
    // db/migration/V1__schema.sql，含 Harness 7 表 + system_setting 默认行），否则缺行会导致上下文启动失败。
    try (Connection connection = newConnection()) {
      resetSchema(connection);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot apply baseline schema", error);
    }
  }

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "true");
    registry.add("kk-studio.harness.dispatcher.poll-interval", () -> "10s");
  }

  @Autowired
  @Qualifier("harnessRuntimeLifecycle")
  private SmartLifecycle harnessRuntimeLifecycle;

  @Autowired private HarnessWorkDispatcher harnessWorkDispatcher;
  @Autowired private PostgresqlNotificationLoop postgresqlNotificationLoop;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void cleanSeededRows() {
    jdbc.update("delete from harness_work");
    jdbc.update("delete from harness_thread where id = ?", THREAD_ID);
    jdbc.update("delete from harness_entry where id = ?", ROOT_ENTRY_ID);
    jdbc.update("delete from harness_session where id = ?", SESSION_ID);
  }

  @Test
  @Order(1)
  void lifecycleStartsWorkersAndDueWorkIsProcessedToCompletion() throws Exception {
    assertTrue(harnessRuntimeLifecycle.isRunning(), "lifecycle must be running");
    assertTrue(postgresqlNotificationLoop.isRunning(), "notification loop must be running");

    seedDueThreadWork();
    jdbc.execute("select pg_notify('harness_runtime_work', '" + THREAD_ID + "')");

    awaitTrue(
        () -> workRowCount(THREAD_ID) == 0,
        5,
        "NOTIFY must wake and complete due THREAD work before the 10s periodic poll");
  }

  @Test
  @Order(2)
  void periodicPollStillProcessesWorkWithoutNotification() throws Exception {
    assertTrue(harnessRuntimeLifecycle.isRunning());
    seedDueThreadWork();

    awaitTrue(
        () -> workRowCount(THREAD_ID) == 0,
        15,
        "periodic poll must recover due THREAD work without a notification");
  }

  @Test
  @Order(3)
  void lifecycleStopStopsDispatcherButKeepsApplicationNotificationLoopRunning() {
    assertTrue(harnessRuntimeLifecycle.isRunning());
    assertTrue(postgresqlNotificationLoop.isRunning());

    harnessRuntimeLifecycle.stop();

    assertFalse(harnessRuntimeLifecycle.isRunning());
    assertTrue(postgresqlNotificationLoop.isRunning());
    assertNotNull(harnessWorkDispatcher, "dispatcher bean stays available after stop");
    harnessRuntimeLifecycle.stop();
  }

  private static final String ROOT_PAYLOAD_JSON =
      """
      {"settings": {"workspacePath": null, "agentName": "lifecycle-test", "model": \
      {"providerName": "openai", "modelName": "gpt-test", "variant": "default"}}, \
      "subagentContext": null}
      """;

  private void seedDueThreadWork() {
    jdbc.update("insert into harness_session (id, created_at) values (?, now())", SESSION_ID);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', ?::jsonb, now())",
        ROOT_ENTRY_ID,
        SESSION_ID,
        ROOT_PAYLOAD_JSON);
    jdbc.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
            + " yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, false, 1, 0, now(), now())",
        THREAD_ID,
        SESSION_ID,
        ROOT_ENTRY_ID,
        CREATION_REQUEST_HASH);
    jdbc.update(
        "insert into harness_work (target_type, target_id, available_at, wake_version)"
            + " values ('THREAD', ?, now(), 1)",
        THREAD_ID);
  }

  private int workRowCount(UUID threadId) {
    return jdbc.queryForObject(
        "select count(*) from harness_work where target_type = 'THREAD' and target_id = ?",
        Integer.class,
        threadId);
  }

  private static void awaitTrue(BooleanSupplier condition, int timeoutSeconds, String message)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    fail(message);
  }

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void resetSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
    }
    // V1 baseline（唯一事实源）提供 Harness 7 表 + system_setting 默认行。
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
