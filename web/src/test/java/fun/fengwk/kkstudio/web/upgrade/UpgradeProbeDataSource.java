package fun.fengwk.kkstudio.web.upgrade;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 升级测试专用的 JDBC 观察基座：包装真实数据源，按打开顺序对连接执行测试钩子，并逐连接记录经 {@link Statement} 执行的 SQL。
 *
 * <p>它替代生产观察点：Flyway 内部直接使用数据源给出的连接（{@code JdbcUtils.openConnection} 只调用 {@code getConnection()}，不做
 * unwrap），因此这里看到的连接与语句就是真实迁移连接上的连接与语句。包内可见（package-private）：只是升级测试的物理基座，不是生产代码。
 */
final class UpgradeProbeDataSource extends AbstractDataSource {

  private final DataSource delegate;

  private final Consumer<Connection> onOpened;

  private final List<List<String>> statementsByConnection =
      Collections.synchronizedList(new ArrayList<>());

  private int opened;

  private UpgradeProbeDataSource(DataSource delegate, Consumer<Connection> onOpened) {
    this.delegate = delegate;
    this.onOpened = onOpened;
  }

  /** 只记录语句、不改写连接的探针。 */
  static UpgradeProbeDataSource recording(DataSource delegate) {
    return new UpgradeProbeDataSource(delegate, connection -> {});
  }

  /** 从第二个连接起注入非 origin 复制角色：第一个连接是本进程 preflight 自己打开的，之后打开的才是真实迁移连接。 */
  static UpgradeProbeDataSource nonOriginAfterTheFirstConnection(DataSource delegate) {
    return new UpgradeProbeDataSource(delegate, UpgradeProbeDataSource::demoteToReplica);
  }

  /** 每个连接被记录到的语句，按 {@link Connection} 打开顺序排列。 */
  List<List<String>> statementsByConnection() {
    synchronized (statementsByConnection) {
      return List.copyOf(statementsByConnection);
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    return open(delegate.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return open(delegate.getConnection(username, password));
  }

  private static void demoteToReplica(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute("set session_replication_role = replica");
    } catch (SQLException error) {
      throw new IllegalStateException(
          "cannot inject the replica replication role into the probe connection", error);
    }
  }

  private Connection open(Connection connection) {
    List<String> recorded;
    synchronized (statementsByConnection) {
      opened++;
      recorded = Collections.synchronizedList(new ArrayList<>());
      statementsByConnection.add(recorded);
      if (opened > 1) {
        onOpened.accept(connection);
      }
    }
    return (Connection)
        Proxy.newProxyInstance(
            UpgradeProbeDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ConnectionHandler(connection, recorded));
  }

  private static final class ConnectionHandler implements InvocationHandler {

    private final Connection connection;

    private final List<String> recorded;

    private ConnectionHandler(Connection connection, List<String> recorded) {
      this.connection = connection;
      this.recorded = recorded;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      if ("createStatement".equals(method.getName())) {
        Statement statement = (Statement) invokeTarget(method, arguments);
        return Proxy.newProxyInstance(
            UpgradeProbeDataSource.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            new StatementHandler(statement, recorded));
      }
      return invokeTarget(method, arguments);
    }

    private Object invokeTarget(Method method, Object[] arguments) throws Throwable {
      try {
        return method.invoke(connection, arguments);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }

  private static final class StatementHandler implements InvocationHandler {

    private final Statement statement;

    private final List<String> recorded;

    private StatementHandler(Statement statement, List<String> recorded) {
      this.statement = statement;
      this.recorded = recorded;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
      switch (method.getName()) {
        case "execute", "executeUpdate", "executeQuery", "executeLargeUpdate", "addBatch" -> {
          if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql) {
            recorded.add(sql);
          }
        }
        default -> {}
      }
      try {
        return method.invoke(statement, arguments);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }
  }
}
