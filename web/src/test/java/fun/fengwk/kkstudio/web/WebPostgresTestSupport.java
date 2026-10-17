package fun.fengwk.kkstudio.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * web 模块 SpringBoot 测试的共享 PostgreSQL 支持。
 *
 * <p>使用进程级单例容器（而非 {@code @Container}），从而跨测试类复用缓存 Spring 上下文时 JDBC URL 保持稳定。本类在静态初始化阶段 即执行 baseline
 * 与 dev seed 迁移，保证任何 Spring 上下文创建前 {@code system_setting} 等表与默认行已经存在 （SystemSettingsSnapshot
 * 在上下文启动时读取权威配置）。
 *
 * <p>共享库的状态边界有两处，二者共同保证「一个 Context 只看见自己的目录」：
 *
 * <ul>
 *   <li>{@link #overrideDataSource} 在每次新 Context 构造前重置到 V1 baseline，因此任何新 Context 的真实启动都走「未初始化形态 →
 *       按自身安装定义与内置贡献初始化 epoch 1」，绝不会恢复上一个 Context 遗留的 manifest（也不会因目录不同而指纹不符）。
 *   <li>{@link #resetAndApplySchema} 在每次测试方法前重置后，立即用本 Context 已冻结的 {@link PluginCatalogSnapshot}
 *       身份（revision、值、 认证代际、上下文指纹、能力指纹）按真实 {@link PluginConfigurationRepository} 的唯一 CAS
 *       入口原样落库，使缓存复用的 Context 与库内权威 catalog 同身份；这里不重新 configure、不猜测默认值、不做生产侧兜底。
 * </ul>
 *
 * <p>需要独立目录的 Runner 测试用 {@link #createIsolatedDatabase()} 在同一个物理容器上取自己的数据库，绝不与共享 web 库共享权威 catalog。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
public abstract class WebPostgresTestSupport {

  private static final String FLYWAY_DISABLED = "false";
  private static final String WORKERS_DISABLED = "false";

  /** V1 唯一未初始化形态的 epoch；baseline 重置后必须以该版本做 CAS 初始化。 */
  private static final long UNINITIALIZED_EPOCH = 0L;

  @MockitoBean private CanvasFunctionDispatcher canvasFunctionDispatcher;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_web_test");

  static {
    POSTGRES.start();
    // SystemSettingsSnapshot 作为共享启动快照，在上下文创建期读取一次 system_setting 默认行；任何 Spring 测试上下文加载前
    // 都必须先有 baseline + dev seed 迁移，否则缺行上下文启动失败。@BeforeEach 仍负责每个测试前的
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

  @Autowired private StudioPluginRegistry baselineRegistry;

  @Autowired private PluginConfigurationRepository baselineRepository;

  @Autowired private PlatformTransactionManager baselineTransactionManager;

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    // 新 Context 构造前先回到 V1 baseline：Boot 期间的真实 PluginCatalogBootstrap 必须按本 Context 的安装定义初始化，
    // 而不是恢复上一个测试类遗留的目录。
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
    persistBaselineCatalog();
  }

  /**
   * 同物理容器上的独立数据库：全新 UUID 库 + baseline/dev seed，供不加载 Web 上下文的 Runner 测试取得自己的 {@link DataSource}。
   *
   * <p>同一个物理容器不等于同一个目录：该库与共享 web 库互不可见，因此 Runner 测试的初始化、提交与恢复都不会污染真正的 web 启动链路。
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

  /** 与本 Context 冻结快照同身份的权威 catalog：身份字段全部来自一次 current 租用的 live 快照，绝不重新 configure 或采用默认值。 */
  private void persistBaselineCatalog() {
    try (PluginCatalogRuntime.Lease lease = baselineRuntime.acquireCurrent()) {
      PluginCatalogSnapshot snapshot = lease.snapshot();
      Map<String, PluginCatalogManifest.Entry> entries = new LinkedHashMap<>();
      List<PluginConfigurationRow> rows = new ArrayList<>();
      for (PluginCatalogSnapshot.ConfiguredPlugin configured : snapshot.configuredPlugins()) {
        String pluginId = configured.descriptor().pluginId();
        StudioPlugin plugin =
            baselineRegistry
                .find(pluginId)
                .orElseThrow(
                    () ->
                        new IllegalStateException("baseline plugin is not installed: " + pluginId));
        String schemaFingerprint =
            PluginCatalogFingerprint.schema(
                plugin.configurationSchema(), plugin.authenticationSpec());
        String capabilityFingerprint = snapshot.capabilityFingerprints().get(pluginId);
        UUID authGeneration = configured.credentialReference().authGeneration();
        String contextFingerprint = configured.credentialReference().contextFingerprint();
        long revision = configured.config().revision();
        String valuesJson = configured.config().valuesJson();
        entries.put(
            pluginId,
            new PluginCatalogManifest.Entry(
                revision,
                configured.descriptor().version(),
                schemaFingerprint,
                capabilityFingerprint,
                authGeneration,
                contextFingerprint));
        rows.add(
            new PluginConfigurationRow(
                pluginId,
                revision,
                configured.descriptor().version(),
                schemaFingerprint,
                capabilityFingerprint,
                authGeneration,
                contextFingerprint,
                List.of(),
                valuesJson));
      }
      PluginCatalogManifest manifest =
          new PluginCatalogManifest(entries, snapshot.catalogFingerprint());
      long expectedEpoch = snapshot.epoch() - 1;
      if (expectedEpoch < UNINITIALIZED_EPOCH) {
        throw new IllegalStateException("baseline snapshot must be an initialized epoch");
      }
      Boolean committed =
          new TransactionTemplate(baselineTransactionManager)
              .execute(status -> baselineRepository.commit(expectedEpoch, rows, manifest));
      if (!Boolean.TRUE.equals(committed)) {
        throw new IllegalStateException("baseline catalog initialization must win the epoch CAS");
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
            "dev seed did not insert agent_definition default-assistant");
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
        .locations("classpath:db/migration", "classpath:db/seed/dev")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
