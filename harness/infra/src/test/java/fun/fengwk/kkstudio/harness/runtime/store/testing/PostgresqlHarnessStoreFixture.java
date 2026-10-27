package fun.fengwk.kkstudio.harness.runtime.store.testing;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 整个进程共用一个 PostgreSQL 容器，并用一次真实 V1 Flyway 迁移生成只读模板库；每个 contract 测试的 reset 都从该模板克隆固定测试库。
 *
 * <p>模板库与测试库只存在于本进程独占的 Testcontainers 容器内。模板库在静态初始化时由 schema 模块的 {@code classpath:db/migration}
 * 迁移一次，随后禁止连接；测试库是唯一允许应用连接、且每次 reset 都会整体删除并重建的库。
 */
final class PostgresqlHarnessStoreFixture {

  /** 模板库名固定为内部常量，不接受测试或外部配置覆盖。 */
  private static final String TEMPLATE_DATABASE = "kk_studio_harness_template";

  /** 应用 DataSource 固定的测试库名；测试不得连接其他数据库，也不得复用 schema 做并行共享。 */
  private static final String TEST_DATABASE = "kk_studio_harness_test";

  private static final Pattern DATABASE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  private static final DataSource ADMIN_DATA_SOURCE;

  private static final DataSource DATA_SOURCE;

  private static final AtomicLong UUID_GENERATOR = new AtomicLong(1);

  static {
    POSTGRES.start();
    ADMIN_DATA_SOURCE = dataSource(POSTGRES.getJdbcUrl());
    initializeTemplate();
    DATA_SOURCE = dataSource(databaseUrl(TEST_DATABASE));
  }

  private PostgresqlHarnessStoreFixture() {}

  static HarnessStore resetAndCreate() {
    reset();
    return create();
  }

  static HarnessStore create() {
    return create(DATA_SOURCE);
  }

  static HarnessStore create(DataSource dataSource) {
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new PostgresqlHarnessStore(dataSource, transactionManager, idGenerator());
  }

  static Supplier<UUID> idGenerator() {
    return () -> new UUID(0L, UUID_GENERATOR.getAndIncrement());
  }

  static DataSource dataSource() {
    return DATA_SOURCE;
  }

  static String templateDatabaseName() {
    return TEMPLATE_DATABASE;
  }

  /**
   * 只执行一次真实 schema 模块 V1 迁移，之后将模板库标记为不可连接。
   *
   * <p>维护约定：模板库只能由本方法写入；测试库的任何 DML、DDL 或迁移历史变更都留在克隆副本中，不得回写模板。
   */
  private static void initializeTemplate() {
    String template = databaseIdentifier(TEMPLATE_DATABASE);
    executeAdmin("CREATE DATABASE " + template + " TEMPLATE template0");
    Flyway.configure()
        .dataSource(databaseUrl(TEMPLATE_DATABASE), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
    // CREATE DATABASE ... TEMPLATE 只复制文件，不需要连接模板库；禁止连接可防止测试误写模板。
    executeAdmin("ALTER DATABASE " + template + " ALLOW_CONNECTIONS false");
  }

  /**
   * 删除并重建固定测试库，使其回到模板库的完整 V1 baseline。
   *
   * <p>串行化与 UUID 重置保持原有契约。遗留连接由 PostgreSQL {@code DROP DATABASE} 自身的活动连接约束拒绝（SQLSTATE 55006），
   * 异常保留数据库 cause；绝不 terminate backend、预查会话或用 {@code DROP DATABASE ... WITH FORCE} 掩盖测试资源泄漏。
   */
  static synchronized void reset() {
    UUID_GENERATOR.set(1L);
    String test = databaseIdentifier(TEST_DATABASE);
    String template = databaseIdentifier(TEMPLATE_DATABASE);
    try (Connection connection = ADMIN_DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(true);
      statement.execute("DROP DATABASE IF EXISTS " + test);
      statement.execute("CREATE DATABASE " + test + " TEMPLATE " + template);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot reset PostgreSQL Harness Store test database", error);
    }
  }

  private static void executeAdmin(String sql) {
    try (Connection connection = ADMIN_DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(true);
      statement.execute(sql);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot initialize PostgreSQL Harness Store template", error);
    }
  }

  private static DataSource dataSource(String url) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(Driver.class.getName());
    dataSource.setUrl(url);
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static String databaseUrl(String database) {
    String url = POSTGRES.getJdbcUrl();
    int queryStart = url.indexOf('?');
    String base = queryStart < 0 ? url : url.substring(0, queryStart);
    String query = queryStart < 0 ? "" : url.substring(queryStart);
    return base.substring(0, base.lastIndexOf('/') + 1) + database + query;
  }

  private static String databaseIdentifier(String database) {
    if (!DATABASE_IDENTIFIER.matcher(database).matches()) {
      throw new IllegalArgumentException("unsafe fixed database identifier: " + database);
    }
    return database;
  }
}
