package fun.fengwk.kkstudio.harness.runtime.store.testing;

import org.postgresql.Driver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** One process-wide PostgreSQL container plus a clean seven-table schema for each contract test. */
final class PostgresqlHarnessStoreFixture {

  private static final String SCHEMA_RESOURCE =
      "fun/fengwk/kkstudio/harness/runtime/spring/postgresql/harness-runtime-schema.sql";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  private static final DataSource DATA_SOURCE;

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
    return new PostgresqlHarnessStore(DATA_SOURCE);
  }

  static DataSource dataSource() {
    return DATA_SOURCE;
  }

  static synchronized void reset() {
    try (Connection connection = DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_RESOURCE));
    } catch (SQLException error) {
      throw new IllegalStateException("cannot reset PostgreSQL Harness Store schema", error);
    }
  }
}
