package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 验证生产 {@code application.yml} 在 Spring Boot 自动配置装配下绑定的 DataSource 属性。
 *
 * <p>测试直接定位编译后的生产配置 {@code target/classes/application.yml}，并通过仅包含 {@link
 * DataSourceAutoConfiguration} 的轻量非 Web 容器进行 Boot runtime binding，避免被测试 classpath 优先加载的测试配置遮蔽。
 */
class PostgresqlDataSourceConfigurationTest {

  @Test
  void productionApplicationYamlBindsHikariAndNetworkTimeoutProperties() throws Exception {
    try (ConfigurableApplicationContext context = productionContext()) {
      DataSource dataSource = context.getBean(DataSource.class);
      assertInstanceOf(
          HikariDataSource.class, dataSource, "configured DataSource must be HikariDataSource");
      HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

      assertEquals(
          5000L,
          hikariDataSource.getConnectionTimeout(),
          "Hikari connection-timeout must be 5000ms");
      assertEquals(1000L, hikariDataSource.getValidationTimeout());
      assertEquals(
          "5",
          hikariDataSource.getDataSourceProperties().getProperty("connectTimeout"),
          "PostgreSQL JDBC connectTimeout must be configured to 5s");
      assertEquals(
          "5",
          hikariDataSource.getDataSourceProperties().getProperty("socketTimeout"),
          "PostgreSQL JDBC socketTimeout must be configured to 5s");
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void lateBorrowOfDeadConnectionFailsWithinClientBudget() throws Exception {
    // 真实 Hikari 池等待至接近 5s 截止再借出坏连接；fake JDBC 像 PG 一样遵守 network timeout。
    // 不缩短生产超时，确保回归同时覆盖 Boot 实际绑定和 borrow 后额外执行的存活校验。
    // 归还会刷新 lastAccess；关闭 Hikari 的短暂免校验优化，确定性模拟需校验的失效连接。
    String bypassProperty = "com.zaxxer.hikari.aliveBypassWindowMs";
    String previousBypass = System.getProperty(bypassProperty);
    System.setProperty(bypassProperty, "-1");
    try (ConfigurableApplicationContext context = productionContext();
        var borrower = Executors.newSingleThreadExecutor()) {
      HikariDataSource pool = context.getBean(HikariDataSource.class);
      DataSource jdbc = mock(DataSource.class);
      Connection physical = mock(Connection.class);
      AtomicBoolean disconnected = new AtomicBoolean();
      AtomicInteger networkTimeout = new AtomicInteger();
      AtomicInteger validationBudget = new AtomicInteger();
      CountDownLatch validationStarted = new CountDownLatch(1);
      when(jdbc.getConnection("unused", "unused"))
          .thenAnswer(
              invocation -> {
                if (disconnected.get()) {
                  throw new SQLException("database disconnected");
                }
                return physical;
              });
      when(physical.getAutoCommit()).thenReturn(true);
      when(physical.getNetworkTimeout()).thenAnswer(invocation -> networkTimeout.get());
      doAnswer(
              invocation -> {
                networkTimeout.set(invocation.getArgument(1));
                return null;
              })
          .when(physical)
          .setNetworkTimeout(any(), anyInt());
      when(physical.isValid(anyInt()))
          .thenAnswer(
              invocation -> {
                if (!disconnected.get()) {
                  return true;
                }
                int budget = networkTimeout.get();
                validationBudget.set(budget);
                validationStarted.countDown();
                // 模拟失联 socket 的有界读等待，而非立即返回的 mock 校验。
                new CountDownLatch(1).await(budget, TimeUnit.MILLISECONDS);
                return false;
              });
      pool.setDataSource(jdbc);
      pool.setMaximumPoolSize(1);
      pool.setMinimumIdle(0);
      try (Connection held = pool.getConnection()) {
        disconnected.set(true);
        long started = System.nanoTime();
        var failure =
            borrower.submit(
                () -> assertThrows(SQLTransientConnectionException.class, pool::getConnection));
        long waitingDeadline = started + TimeUnit.SECONDS.toNanos(1);
        while (pool.getHikariPoolMXBean().getThreadsAwaitingConnection() != 1
            && System.nanoTime() < waitingDeadline) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertEquals(1, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        // 用单调时钟控制故障时序：在 5s 等待预算的最后 500ms 归还连接。
        long releaseAt = started + TimeUnit.MILLISECONDS.toNanos(4500);
        new CountDownLatch(1)
            .await(Math.max(0, releaseAt - System.nanoTime()), TimeUnit.NANOSECONDS);
        held.close();
        assertTrue(validationStarted.await(1, TimeUnit.SECONDS), "late borrow must validate");
        failure.get(
            10_000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            TimeUnit.MILLISECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(1000, validationBudget.get(), "bound production validation budget");
        assertTrue(elapsedMillis >= 5000, "must exercise validation beyond pool wait budget");
        assertTrue(elapsedMillis < 10_000, "must fail before the unchanged E2E client deadline");
      }
    } finally {
      if (previousBypass == null) {
        System.clearProperty(bypassProperty);
      } else {
        System.setProperty(bypassProperty, previousBypass);
      }
    }
  }

  private static ConfigurableApplicationContext productionContext() throws Exception {
    Path configPath =
        Path.of(WebApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("application.yml")
            .toAbsolutePath()
            .normalize();
    assertTrue(
        Files.isRegularFile(configPath),
        () -> "production application.yml must exist at " + configPath);

    return new SpringApplicationBuilder(TestDataSourceConfig.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.config.location=file:" + configPath,
            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/unused",
            "--spring.datasource.username=unused",
            "--spring.datasource.password=unused");
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
  static class TestDataSourceConfig {}
}
