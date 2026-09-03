package fun.fengwk.kkstudio.harness.runtime.store.testing;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PostgreSQL 测试专用 CTE 查询监控 helper。
 *
 * <p>通过轻量 JDK Dynamic Proxy 监控 PreparedStatement 实际执行，精确计数包含 {@code with recursive entry_path} 的查询。
 */
final class PostgresqlEntryPathCteCounter {

  private PostgresqlEntryPathCteCounter() {}

  static DataSource countingDataSource(DataSource target, AtomicInteger counter) {
    return (DataSource)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (result instanceof Connection connection) {
                return countingConnection(connection, counter);
              }
              return result;
            });
  }

  private static Connection countingConnection(Connection target, AtomicInteger counter) {
    return (Connection)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (result instanceof PreparedStatement ps
                  && args != null
                  && args.length > 0
                  && args[0] instanceof String sql
                  && sql.toLowerCase(Locale.ROOT).contains("with recursive entry_path")) {
                return countingPreparedStatement(ps, counter);
              }
              return result;
            });
  }

  private static PreparedStatement countingPreparedStatement(
      PreparedStatement target, AtomicInteger counter) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              if (method.getName().startsWith("execute")) {
                counter.incrementAndGet();
              }
              return invoke(target, method, args);
            });
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getTargetException();
    }
  }
}
