package fun.fengwk.kkstudio.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class PostgresqlPersistenceConfigurationTest {

  @Test
  void firstSqlExceptionsKeepPostgresqlClassificationWithoutMetadataConnections() throws Exception {
    // 每种状态使用全新真实 JdbcTemplate：首次 SQL 异常不能触发第二次连接获取或元数据访问。
    assertClassification("23505", DuplicateKeyException.class);
    assertClassification("23503", DataIntegrityViolationException.class);
    assertClassification("08006", DataAccessResourceFailureException.class);
  }

  @Test
  void concurrentFirstExceptionsOnlyAcquireTheirOriginalOperationConnections() throws Exception {
    // 屏障让多个真实 JDBC 执行同时抛错；共享异常转换器不能串行访问 DataSource 来初始化。
    int callers = 4;
    CountDownLatch executing = new CountDownLatch(callers);
    List<Connection> connections = new CopyOnWriteArrayList<>();
    DataSource jdbc = failingJdbc("08006", executing, connections);
    JdbcTemplate template = template(jdbc);
    try (var executor = Executors.newFixedThreadPool(callers)) {
      var failures = new ArrayList<Future<RuntimeException>>();
      for (int i = 0; i < callers; i++) {
        failures.add(
            executor.submit(
                () ->
                    assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> template.queryForObject("select 1", Integer.class))));
      }
      for (var failure : failures) {
        assertThat(failure.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class);
      }
    }
    verify(jdbc, times(callers)).getConnection();
    assertClosedWithoutMetadata(connections, callers);
  }

  @Test
  void bootAutoConfiguredJdbcClientUsesPostgresqlTranslatorWithoutMetadataConnections()
      throws Exception {
    // 生产装配由 Boot 自动配置 Stock JdbcClient；平台的翻译器 Bean 必须被采用，故障路径不读元数据。
    List<Connection> connections = new CopyOnWriteArrayList<>();
    DataSource jdbc = failingJdbc("23505", null, connections);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class))
        .withUserConfiguration(PostgresqlPersistenceConfiguration.class)
        .withBean("dataSource", DataSource.class, () -> jdbc)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(JdbcClient.class);
              verify(jdbc, never()).getConnection();
              JdbcClient client = context.getBean(JdbcClient.class);
              RuntimeException failure =
                  assertThrows(
                      DuplicateKeyException.class,
                      () -> client.sql("select 1").query(Integer.class).single());
              assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
              assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("23505");
              verify(jdbc, times(1)).getConnection();
              assertClosedWithoutMetadata(connections, 1);
            });
  }

  private static void assertClassification(String sqlState, Class<? extends RuntimeException> type)
      throws Exception {
    List<Connection> connections = new CopyOnWriteArrayList<>();
    DataSource jdbc = failingJdbc(sqlState, null, connections);
    JdbcTemplate template = template(jdbc);
    verify(jdbc, never()).getConnection();
    RuntimeException failure =
        assertThrows(type, () -> template.queryForObject("select 1", Integer.class));
    assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
    assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo(sqlState);
    verify(jdbc, times(1)).getConnection();
    assertClosedWithoutMetadata(connections, 1);
  }

  private static JdbcTemplate template(DataSource jdbc) {
    JdbcTemplate template = new JdbcTemplate(jdbc);
    template.setExceptionTranslator(
        new PostgresqlPersistenceConfiguration().postgresqlSqlExceptionTranslator());
    return template;
  }

  private static DataSource failingJdbc(
      String state, CountDownLatch executing, List<Connection> connections) throws Exception {
    DataSource jdbc = mock(DataSource.class);
    when(jdbc.getConnection())
        .thenAnswer(
            invocation -> {
              Connection connection = mock(Connection.class);
              PreparedStatement statement = mock(PreparedStatement.class);
              when(connection.getAutoCommit()).thenReturn(true);
              // JdbcTemplate 的静态 SQL 走 createStatement，JdbcClient/命名参数走 prepareStatement。
              when(connection.createStatement()).thenReturn(statement);
              when(connection.prepareStatement(anyString())).thenReturn(statement);
              when(statement.executeQuery(anyString()))
                  .thenAnswer(
                      call -> {
                        throw failure(state, executing);
                      });
              when(statement.executeQuery())
                  .thenAnswer(
                      call -> {
                        throw failure(state, executing);
                      });
              connections.add(connection);
              return connection;
            });
    return jdbc;
  }

  private static SQLException failure(String state, CountDownLatch executing) throws SQLException {
    if (executing != null) {
      executing.countDown();
      try {
        if (!executing.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("all JDBC operations must reach the failure barrier");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for the failure barrier", interrupted);
      }
    }
    return new SQLException("controlled SQL failure", state);
  }

  private static void assertClosedWithoutMetadata(List<Connection> connections, int expected)
      throws Exception {
    assertThat(connections).hasSize(expected);
    for (Connection connection : connections) {
      verify(connection, never()).getMetaData();
      verify(connection).close();
    }
  }
}
