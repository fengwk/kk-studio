package fun.fengwk.kkstudio.harness.runtime.store.testing;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 整个进程共用一个 PostgreSQL 容器，并为每个 contract 测试应用唯一 V1 baseline（database 模块）。 */
final class PostgresqlHarnessStoreFixture {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  private static final DataSource DATA_SOURCE;

  private static final AtomicLong UUID_GENERATOR = new AtomicLong(1);

  static {
    POSTGRES.start();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(Driver.class.getName());
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    DATA_SOURCE = dataSource;
  }

  private PostgresqlHarnessStoreFixture() {}

  static HarnessStore resetAndCreate() {
    reset();
    return create();
  }

  static HarnessStore create() {
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(DATA_SOURCE);
    return new PostgresqlHarnessStore(DATA_SOURCE, transactionManager, idGenerator());
  }

  static Supplier<UUID> idGenerator() {
    return () -> new UUID(0L, UUID_GENERATOR.getAndIncrement());
  }

  static DataSource dataSource() {
    return DATA_SOURCE;
  }

  static synchronized void reset() {
    UUID_GENERATOR.set(1L);
    try (Connection connection = DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
      // 唯一 V1 baseline（database 模块）：与生产 bootstrap 同一 Flyway 机制应用。
      Flyway.configure()
          .dataSource(DATA_SOURCE)
          .locations("classpath:db/migration")
          .validateMigrationNaming(true)
          .load()
          .migrate();
    } catch (SQLException error) {
      throw new IllegalStateException("cannot reset PostgreSQL Harness Store schema", error);
    }
  }
}
