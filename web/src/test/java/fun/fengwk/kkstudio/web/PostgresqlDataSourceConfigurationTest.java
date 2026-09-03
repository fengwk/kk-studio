package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证生产 {@code application.yml} 在 Spring Boot 自动配置装配下绑定的 DataSource 属性。
 *
 * <p>测试直接定位编译后的生产配置 {@code target/classes/application.yml}，并通过仅包含 {@link
 * DataSourceAutoConfiguration} 的轻量非 Web 容器进行 Boot runtime binding，避免被测试 classpath 优先加载的测试配置遮蔽。
 */
class PostgresqlDataSourceConfigurationTest {

  @Test
  void productionApplicationYamlBindsHikariAndNetworkTimeoutProperties() throws Exception {
    Path configPath =
        Path.of(WebApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("application.yml")
            .toAbsolutePath()
            .normalize();
    assertTrue(
        Files.isRegularFile(configPath),
        () -> "production application.yml must exist at " + configPath);

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(TestDataSourceConfig.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.config.location=file:" + configPath,
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/unused",
                "--spring.datasource.username=unused",
                "--spring.datasource.password=unused")) {
      DataSource dataSource = context.getBean(DataSource.class);
      assertInstanceOf(
          HikariDataSource.class, dataSource, "configured DataSource must be HikariDataSource");
      HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

      assertEquals(
          5000L,
          hikariDataSource.getConnectionTimeout(),
          "Hikari connection-timeout must be 5000ms");
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

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
  static class TestDataSourceConfig {}
}
