package fun.fengwk.kkstudio.harness.infra.store;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 拦截生产 {@code entry_slice} 查询，用同一参数做 {@code EXPLAIN ANALYZE}。
 *
 * <p>保存生产语句的真实计划文本。命中游标时递归过滤不得出现 ROOT，且 {@code harness_entry} 的实际读取行数只覆盖 head 到游标；该计划属于本次生产
 * SQL，不是另一条对照查询。
 */
final class PostgresqlEntrySliceProbe {

  private PostgresqlEntrySliceProbe() {}

  static DataSource probingDataSource(DataSource target, AtomicReference<String> plan) {
    return (DataSource)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (result instanceof Connection connection) {
                return probingConnection(connection, plan);
              }
              return result;
            });
  }

  private static Connection probingConnection(Connection target, AtomicReference<String> plan) {
    return (Connection)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (result instanceof PreparedStatement statement
                  && args != null
                  && args.length > 0
                  && args[0] instanceof String sql
                  && isSliceQuery(sql)) {
                return probingStatement(target, statement, sql, plan);
              }
              return result;
            });
  }

  private static PreparedStatement probingStatement(
      Connection connection, PreparedStatement target, String sql, AtomicReference<String> plan) {
    UUID[] bound = new UUID[2];
    return (PreparedStatement)
        Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              if ((method.getName().equals("setObject") || method.getName().equals("setString"))
                  && args != null
                  && args.length >= 2
                  && args[0] instanceof Integer index
                  && index >= 1
                  && index <= bound.length
                  && args[1] != null) {
                bound[index - 1] = UUID.fromString(args[1].toString());
              }
              if (method.getName().startsWith("execute")) {
                plan.set(explain(connection, sql, bound));
              }
              return invoke(target, method, args);
            });
  }

  private static String explain(Connection connection, String sql, UUID[] bound) throws Exception {
    String probe = "explain (analyze, format text) " + withoutTrailingSemicolon(sql);
    try (PreparedStatement explain = connection.prepareStatement(probe)) {
      for (int i = 0; i < bound.length; i++) {
        explain.setObject(i + 1, bound[i]);
      }
      try (ResultSet rows = explain.executeQuery()) {
        StringBuilder text = new StringBuilder();
        while (rows.next()) {
          String line = rows.getString(1);
          if (line.contains("Filter:")) {
            text.append(line.strip()).append('\n');
          }
        }
        return text.toString();
      }
    }
  }

  private static boolean isSliceQuery(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    return normalized.contains("with recursive entry_slice") && !normalized.contains("explain");
  }

  private static String withoutTrailingSemicolon(String sql) {
    String trimmed = sql.stripTrailing();
    return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getTargetException();
    }
  }
}
