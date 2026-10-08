package fun.fengwk.kkstudio.harness.runtime.store.testing;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 整个进程共用一个 PostgreSQL 容器，并为每个 contract 测试应用唯一 V1 baseline（schema 模块）。 */
public final class PostgresqlHarnessStoreFixture {

  public static final List<NotificationTopic<?>> ALL_TOPICS =
      List.of(
          HarnessNotifications.WORK_AVAILABLE,
          HarnessNotifications.THREAD_VERSION,
          HarnessNotifications.THREAD_TREE,
          HarnessNotifications.TOOL_INTERACTION,
          HarnessNotifications.REALTIME);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  private static final DataSource DATA_SOURCE;

  private static final AtomicLong UUID_GENERATOR = new AtomicLong(1);
  private static DefaultNotificationBus activeBus;

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

  public static synchronized HarnessStore resetAndCreate() {
    reset();
    return create();
  }

  public static synchronized HarnessStore create() {
    return create(DATA_SOURCE);
  }

  public static synchronized HarnessStore create(DataSource dataSource) {
    if (activeBus != null) {
      activeBus.close();
      activeBus = null;
    }
    DefaultNotificationBus bus = newBus(dataSource);
    activeBus = bus;
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new PostgresqlHarnessStore(dataSource, transactionManager, idGenerator(), bus);
  }

  public static synchronized DefaultNotificationBus notificationBus() {
    if (activeBus == null) {
      activeBus = newBus(DATA_SOURCE);
    }
    return activeBus;
  }

  public static DefaultNotificationBus newBus() {
    return newBus(DATA_SOURCE);
  }

  public static DefaultNotificationBus newBus(DataSource dataSource) {
    return new DefaultNotificationBus(
        dataSource,
        UUID.randomUUID(),
        ALL_TOPICS,
        NotificationLimits.defaults(),
        Duration.ofMillis(50),
        Duration.ofMillis(50));
  }

  public static Supplier<UUID> idGenerator() {
    return () -> new UUID(0L, UUID_GENERATOR.getAndIncrement());
  }

  public static DataSource dataSource() {
    return DATA_SOURCE;
  }

  public static synchronized void reset() {
    if (activeBus != null) {
      activeBus.close();
      activeBus = null;
    }
    UUID_GENERATOR.set(1L);
    try (Connection connection = DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
      // 唯一 V1 baseline（schema 模块）：与生产 bootstrap 同一 Flyway 机制应用。
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
