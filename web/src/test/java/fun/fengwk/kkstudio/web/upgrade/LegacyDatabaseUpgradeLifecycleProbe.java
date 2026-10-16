package fun.fengwk.kkstudio.web.upgrade;

import org.postgresql.PGConnection;

import javax.sql.DataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 生命周期与结果语义测试用的窄委托探针：把一个真实 {@link DataSource} 包成「目标数据库调用记录器 + 确定性故障注入点」。
 *
 * <p>除被显式注入的方法外，每一次调用都原样落到真实 JDBC 对象上，不模拟任何 SQL 语义、不伪造 SQLState；因此被测内核看到的仍是真实连接、真实事务与真实
 * PostgreSQL。探针只额外记录事实：DataSource 取连接的次数、每个语句/执行的 SQL 原文、commit/rollback/close
 * 的尝试顺序，以及最终物理连接是否真的关闭。
 *
 * <p>注入点按 SQL 子串匹配（匹配即抛出调用方给定的同一个实例），另有三类协议级注入：COPY 前置（{@code unwrap(PGConnection)} 失败）、{@code
 * rollback} 失败、{@code close} 真实执行后再报失败、以及「真实 commit 已落盘但把回执当成失败」。
 *
 * <p>包内可见（package-private）：只是升级测试的故障探针，不是生产代码。
 */
final class LegacyDatabaseUpgradeLifecycleProbe {

  private final String label;

  private final DataSource real;

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());

  private final Set<Object> openStatements = new CopyOnWriteArraySet<>();

  private final Map<String, Supplier<Throwable>> sqlFailures = new LinkedHashMap<>();

  private final AtomicInteger connectionsOpened = new AtomicInteger();

  private volatile Connection realConnection;

  private Supplier<Throwable> copyApiFailure;

  private Supplier<Throwable> rollbackFailure;

  private Supplier<Throwable> closeFailure;

  private Supplier<Throwable> commitAcknowledgementFailure;

  private LegacyDatabaseUpgradeLifecycleProbe(String label, DataSource real) {
    this.label = label;
    this.real = real;
  }

  static LegacyDatabaseUpgradeLifecycleProbe of(String label, DataSource real) {
    return new LegacyDatabaseUpgradeLifecycleProbe(label, real);
  }

  /** 注入一：任何语句的 SQL 文本包含该子串时，抛出给定实例（同一个实例，便于身份断言）。 */
  LegacyDatabaseUpgradeLifecycleProbe failOnSql(String sqlFragment, Supplier<Throwable> failure) {
    sqlFailures.put(sqlFragment, failure);
    return this;
  }

  /** 注入二：内核取 COPY API 时就失败，用于模拟「事务中途 COPY 失败」。 */
  LegacyDatabaseUpgradeLifecycleProbe failCopyApi(Supplier<Throwable> failure) {
    copyApiFailure = failure;
    return this;
  }

  /** 注入三：{@code rollback} 失败且不落到真实连接，用于模拟「回滚结果也无法确认」。 */
  LegacyDatabaseUpgradeLifecycleProbe failRollback(Supplier<Throwable> failure) {
    rollbackFailure = failure;
    return this;
  }

  /** 注入四：真实 {@code close} 已经执行、但调用方仍看到失败，用于模拟「释放资源时报错」。 */
  LegacyDatabaseUpgradeLifecycleProbe failClose(Supplier<Throwable> failure) {
    closeFailure = failure;
    return this;
  }

  /** 注入五：真实 {@code commit} 已经落到 PostgreSQL、但客户端把回执当成失败，用于模拟通信丢包。 */
  LegacyDatabaseUpgradeLifecycleProbe failCommitAcknowledgement(Supplier<Throwable> failure) {
    commitAcknowledgementFailure = failure;
    return this;
  }

  DataSource dataSource() {
    return (DataSource)
        Proxy.newProxyInstance(
            LegacyDatabaseUpgradeLifecycleProbe.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            this::invokeDataSource);
  }

  List<String> events() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  /** 事件序列中出现过该子串。 */
  boolean sawEvent(String fragment) {
    return events.stream().anyMatch(event -> event.contains(fragment));
  }

  int countEvents(String fragment) {
    return (int) events.stream().filter(event -> event.contains(fragment)).count();
  }

  int connectionsOpened() {
    return connectionsOpened.get();
  }

  int openStatements() {
    return openStatements.size();
  }

  boolean realConnectionClosed() {
    Connection connection = realConnection;
    try {
      return connection != null && connection.isClosed();
    } catch (SQLException error) {
      return false;
    }
  }

  /** 测试收尾用：内核若泄漏了连接，这里仍然释放它，避免影响同容器内的其它用例（断言已在此之前完成）。 */
  void releaseRemaining() {
    Connection connection = realConnection;
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // 收尾清理的失败不改变任何断言结论。
    }
  }

  private Object invokeDataSource(Object proxy, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if ("equals".equals(name)) {
      return proxy == args[0];
    }
    if ("hashCode".equals(name)) {
      return System.identityHashCode(proxy);
    }
    if ("toString".equals(name)) {
      return "LegacyDatabaseUpgradeLifecycleProbe[" + label + "]";
    }
    if ("getConnection".equals(name)) {
      connectionsOpened.incrementAndGet();
      events.add("getConnection");
      Connection connection = (Connection) invoke(real, method, args);
      realConnection = connection;
      return connectionProxy(connection);
    }
    return invoke(real, method, args);
  }

  private Object connectionProxy(Connection delegate) {
    return Proxy.newProxyInstance(
        LegacyDatabaseUpgradeLifecycleProbe.class.getClassLoader(),
        new Class<?>[] {Connection.class, PGConnection.class},
        (proxy, method, args) -> invokeConnection(proxy, delegate, method, args));
  }

  private Object invokeConnection(Object proxy, Connection delegate, Method method, Object[] args)
      throws Throwable {
    String name = method.getName();
    if ("equals".equals(name)) {
      return proxy == args[0];
    }
    if ("hashCode".equals(name)) {
      return System.identityHashCode(proxy);
    }
    if ("toString".equals(name)) {
      return "LegacyDatabaseUpgradeLifecycleProbe[" + label + "].connection";
    }
    if ("close".equals(name)) {
      events.add("close-connection");
      Object result = invoke(delegate, method, args);
      return throwIfInjected(closeFailure, result);
    }
    if ("rollback".equals(name)) {
      events.add("rollback");
      Supplier<Throwable> failure = rollbackFailure;
      if (failure != null) {
        throw failure.get();
      }
      return invoke(delegate, method, args);
    }
    if ("commit".equals(name)) {
      events.add("commit");
      Object result = invoke(delegate, method, args);
      return throwIfInjected(commitAcknowledgementFailure, result);
    }
    if ("unwrap".equals(name)) {
      Supplier<Throwable> failure = copyApiFailure;
      if (failure != null && args.length == 1 && args[0] == PGConnection.class) {
        events.add("unwrap-copy-api");
        throw failure.get();
      }
      return invoke(delegate, method, args);
    }
    if (isStatementFactory(name)) {
      String sql =
          args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
      events.add("prepare:" + sql);
      for (Map.Entry<String, Supplier<Throwable>> entry : sqlFailures.entrySet()) {
        if (sql.contains(entry.getKey())) {
          throw entry.getValue().get();
        }
      }
      Object statement = invoke(delegate, method, args);
      return statementProxy((Statement) statement, sql);
    }
    return invoke(delegate, method, args);
  }

  private Object statementProxy(Statement delegate, String sql) {
    Object proxy =
        Proxy.newProxyInstance(
            LegacyDatabaseUpgradeLifecycleProbe.class.getClassLoader(),
            new Class<?>[] {Statement.class, PreparedStatement.class, CallableStatement.class},
            (statement, method, args) -> invokeStatement(statement, delegate, sql, method, args));
    openStatements.add(proxy);
    return proxy;
  }

  private Object invokeStatement(
      Object proxy, Statement delegate, String sql, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if ("equals".equals(name)) {
      return proxy == args[0];
    }
    if ("hashCode".equals(name)) {
      return System.identityHashCode(proxy);
    }
    if ("toString".equals(name)) {
      return "LegacyDatabaseUpgradeLifecycleProbe[" + label + "].statement";
    }
    if ("close".equals(name)) {
      openStatements.remove(proxy);
      events.add("close-statement");
      return invoke(delegate, method, args);
    }
    if (name.startsWith("execute") || "addBatch".equals(name)) {
      String executed = firstSqlArgument(args, sql);
      events.add("execute:" + executed);
      for (Map.Entry<String, Supplier<Throwable>> entry : sqlFailures.entrySet()) {
        if (executed.contains(entry.getKey())) {
          throw entry.getValue().get();
        }
      }
    }
    return invoke(delegate, method, args);
  }

  /** 语句执行时的 SQL 优先取本次调用的字符串参数（{@code Statement.execute(sql)}），否则沿用创建时的 SQL。 */
  private static String firstSqlArgument(Object[] args, String fallback) {
    if (args != null && args.length > 0 && args[0] instanceof String) {
      return (String) args[0];
    }
    return fallback;
  }

  private static boolean isStatementFactory(String name) {
    return "createStatement".equals(name)
        || "prepareStatement".equals(name)
        || "prepareCall".equals(name);
  }

  private static Object throwIfInjected(Supplier<Throwable> failure, Object result)
      throws Throwable {
    if (failure != null) {
      throw failure.get();
    }
    return result;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getTargetException();
    }
  }
}
