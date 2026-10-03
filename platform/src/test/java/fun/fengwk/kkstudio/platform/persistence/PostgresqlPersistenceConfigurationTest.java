package fun.fengwk.kkstudio.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

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
    // 每种状态使用全新真实模板：首次 SQL 异常不能触发第二次连接获取或元数据访问。
    assertClassification("23505", DuplicateKeyException.class);
    assertClassification("23503", DataIntegrityViolationException.class);
    assertClassification("08006", DataAccessResourceFailureException.class);
  }

  private static void assertClassification(String sqlState, Class<? extends RuntimeException> type)
      throws Exception {
    List<Connection> connections = new CopyOnWriteArrayList<>();
    DataSource jdbc = failingJdbc(sqlState, null, connections);
    SqlSessionTemplate template =
        new PostgresqlPersistenceConfiguration()
            .sqlSessionTemplate(factory(jdbc, ExecutorType.SIMPLE), new MybatisProperties());
    verify(jdbc, never()).getConnection();
    RuntimeException failure =
        assertThrows(type, () -> template.getMapper(FailingMapper.class).read());
    assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
    assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo(sqlState);
    verify(jdbc, times(1)).getConnection();
    assertClosedWithoutMetadata(connections, 1);
  }

  @Test
  void concurrentFirstExceptionsOnlyAcquireTheirOriginalOperationConnections() throws Exception {
    // 屏障让多个真实 JDBC 执行同时抛错；共享异常转换器不能串行访问 DataSource 来初始化。
    int callers = 4;
    CountDownLatch executing = new CountDownLatch(callers);
    List<Connection> connections = new CopyOnWriteArrayList<>();
    DataSource jdbc = failingJdbc("08006", executing, connections);
    SqlSessionTemplate template =
        new PostgresqlPersistenceConfiguration()
            .sqlSessionTemplate(factory(jdbc, ExecutorType.SIMPLE), new MybatisProperties());
    try (var executor = Executors.newFixedThreadPool(callers)) {
      var failures = new ArrayList<Future<RuntimeException>>();
      for (int i = 0; i < callers; i++) {
        failures.add(
            executor.submit(
                () ->
                    assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> template.getMapper(FailingMapper.class).read())));
      }
      for (var failure : failures) {
        assertThat(failure.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class);
      }
    }
    verify(jdbc, times(callers)).getConnection();
    assertClosedWithoutMetadata(connections, callers);
  }

  @ParameterizedTest
  @CsvSource({"SIMPLE,,SIMPLE", "BATCH,,BATCH", "SIMPLE,BATCH,BATCH", "BATCH,SIMPLE,SIMPLE"})
  void bootPropertiesOverrideFactoryDefaultWithoutChangingExecutorSemantics(
      ExecutorType factoryDefault, ExecutorType property, ExecutorType expected) {
    // 验证真实 Boot 属性绑定及默认模板退让；未指定 property 时保留 factory 的默认 executor。
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisAutoConfiguration.class))
            .withUserConfiguration(PostgresqlPersistenceConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(
                SqlSessionFactory.class, () -> factory(mock(DataSource.class), factoryDefault));
    if (property != null) {
      runner = runner.withPropertyValues("mybatis.executor-type=" + property);
    }
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(SqlSessionTemplate.class);
          SqlSessionTemplate template = context.getBean(SqlSessionTemplate.class);
          assertThat(template.getExecutorType()).isEqualTo(expected);
          assertThat(
                  context
                      .getBeanFactory()
                      .getBeanDefinition("sqlSessionTemplate")
                      .getFactoryBeanName())
              .isEqualTo("postgresqlPersistenceConfiguration");
        });
  }

  private static SqlSessionFactory factory(DataSource jdbc, ExecutorType defaultExecutor) {
    Configuration config = new Configuration();
    config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), jdbc));
    config.setDefaultExecutorType(defaultExecutor);
    config.addMapper(FailingMapper.class);
    return new SqlSessionFactoryBuilder().build(config);
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
              when(connection.prepareStatement(anyString())).thenReturn(statement);
              when(statement.execute())
                  .thenAnswer(
                      call -> {
                        if (executing != null) {
                          executing.countDown();
                          if (!executing.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError(
                                "all JDBC operations must reach the failure barrier");
                          }
                        }
                        throw new SQLException("controlled SQL failure", state);
                      });
              connections.add(connection);
              return connection;
            });
    return jdbc;
  }

  private static void assertClosedWithoutMetadata(List<Connection> connections, int expected)
      throws Exception {
    assertThat(connections).hasSize(expected);
    for (Connection connection : connections) {
      verify(connection, never()).getMetaData();
      verify(connection).close();
    }
  }

  public interface FailingMapper {
    @Select("select 1")
    int read();
  }
}
