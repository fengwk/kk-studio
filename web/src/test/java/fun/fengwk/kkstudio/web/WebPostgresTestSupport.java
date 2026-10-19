package fun.fengwk.kkstudio.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * web 模块 SpringBoot 测试的共享 PostgreSQL 支持。
 *
 * <p>使用进程级单例容器（而非 {@code @Container}），从而跨测试类复用缓存 Spring 上下文时 JDBC URL 保持稳定。本类在静态初始化阶段 即执行 baseline
 * 与 fixture seed 迁移，保证任何 Spring 上下文创建前 {@code system_setting} 等表与默认行已经存在 （SystemSettingsSnapshot
 * 在上下文启动时读取权威配置）。
 *
 * <p>共享库的状态边界有两处，二者共同保证「一个 Context 只看见自己的插件配置」：
 *
 * <ul>
 *   <li>{@link #overrideDataSource} 在每次新 Context 构造前重置到 baseline，因此任何新 Context 启动时都按自身安装定义写入默认配置。
 *   <li>{@link #resetAndApplySchema} 在每次测试方法前重置后，按本 Context 当前 {@link PluginCatalogSnapshot}
 *       的修订与值原样写回 {@code plugin_configuration}，使缓存复用的 Context 与库内配置一致；这里不重新 configure。
 * </ul>
 *
 * <p>需要独立配置的 Runner 测试用 {@link #createIsolatedDatabase()} 在同一个物理容器上取自己的数据库。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
public abstract class WebPostgresTestSupport {

  private static final String FLYWAY_DISABLED = "false";
  private static final String WORKERS_DISABLED = "false";

  @MockitoBean private CanvasFunctionDispatcher canvasFunctionDispatcher;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_web_test");

  static {
    POSTGRES.start();
    // SystemSettingsSnapshot 作为共享启动快照，在上下文创建期读取一次 system_setting 默认行；任何 Spring 测试上下文加载前
    // 都必须先有 baseline + fixture seed 迁移，否则缺行上下文启动失败。@BeforeEach 仍负责每个测试前的
    // reset+remigrate。
    try (Connection conn = newConnection()) {
      resetAndMigrateDevDatabase(conn);
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  /** 共享 PG 容器的 JDBC 地址；供需要额外应用上下文（跨节点集成）的测试复用同一实例。 */
  protected static String sharedJdbcUrl() {
    return POSTGRES.getJdbcUrl();
  }

  protected static String sharedJdbcUsername() {
    return POSTGRES.getUsername();
  }

  protected static String sharedJdbcPassword() {
    return POSTGRES.getPassword();
  }

  @Autowired private PluginCatalogRuntime baselineRuntime;

  @Autowired private JdbcClient baselineJdbc;

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    // 新 Context 构造前先回到 baseline：启动时按本 Context 的安装定义写入默认配置，而不是沿用上一个测试类的配置。
    try (Connection conn = newConnection()) {
      resetAndMigrateDevDatabase(conn);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot reset the shared web test database", error);
    }
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> FLYWAY_DISABLED);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
  }

  @BeforeEach
  final void resetAndApplySchema() throws Exception {
    try (Connection conn = newConnection()) {
      resetAndMigrateDevDatabase(conn);
      verifyDevSeed(conn);
    }
    persistBaselineConfigurations();
  }

  /**
   * 同物理容器上的独立数据库：全新 UUID 库 + baseline/fixture seed，供不加载 Web 上下文的 Runner 测试取得自己的 {@link
   * DataSource}；该库与共享 web 库互不可见。
   */
  public static DataSource createIsolatedDatabase() {
    String database = "kk_studio_web_isolated_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection conn =
            DriverManager.getConnection(
                databaseUrl(POSTGRES.getDatabaseName()),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Statement statement = conn.createStatement()) {
      statement.execute("create database " + database);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot create an isolated test database", error);
    }
    DataSource dataSource =
        new SimpleDriverDataSource(
            new Driver(), databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (Connection conn = dataSource.getConnection()) {
      migrateDevDatabase(conn);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot migrate the isolated test database", error);
    }
    return dataSource;
  }

  /** 按本 Context 当前快照的修订与值写回插件配置，使库内配置与运行期快照一致。 */
  private void persistBaselineConfigurations() {
    try (PluginCatalogRuntime.Lease lease = baselineRuntime.acquire()) {
      for (PluginCatalogSnapshot.ConfiguredPlugin configured :
          lease.snapshot().configuredPlugins()) {
        baselineJdbc
            .sql(
                "insert into plugin_configuration (plugin_id, revision, values_json)"
                    + " values (:pluginId, :revision, :valuesJson)")
            .param("pluginId", configured.descriptor().pluginId())
            .param("revision", configured.config().revision())
            .param("valuesJson", configured.config().valuesJson())
            .update();
      }
    }
  }

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** 用容器暴露的真实 JDBC URL 替换库名，不自己拼主机与端口。 */
  private static String databaseUrl(String database) {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    int prefix = "jdbc:postgresql://".length();
    int pathStart = jdbcUrl.indexOf('/', prefix);
    int queryStart = jdbcUrl.indexOf('?', pathStart);
    String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
    return jdbcUrl.substring(0, pathStart + 1) + database + suffix;
  }

  private static void resetAndMigrateDevDatabase(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("drop schema public cascade");
      st.execute("create schema public");
    }
    migrateDevDatabase(conn);
  }

  private static void verifyDevSeed(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select count(*) from agent_definition where name = 'default-assistant'")) {
      if (!rs.next() || rs.getLong(1) != 1L) {
        throw new IllegalStateException(
            "fixture seed did not insert agent_definition default-assistant");
      }
    }
  }

  private static void migrateDevDatabase(Connection conn) {
    // suppressClose 用于保留调用方管理的 JDBC 连接。
    migrateDevDatabase(new SingleConnectionDataSource(conn, true));
  }

  private static void migrateDevDatabase(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration", "classpath:db/seed/fixture")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
