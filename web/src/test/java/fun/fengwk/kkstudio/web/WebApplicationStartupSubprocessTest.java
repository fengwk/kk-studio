package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 只走生产入口：一次性 PostgreSQL 17 与私有 MinIO，不发起任何上游调用。 */
class WebApplicationStartupSubprocessTest {
  @TempDir Path temp;

  @Test
  void retiredCommandFailsBeforeSpringOrDatabaseAccess() throws Exception {
    Path output = temp.resolve("retired.log");
    ProcessBuilder builder = new ProcessBuilder(command("database-upgrade", "--help"));
    cleanEnvironment(builder);
    builder.environment().put("KK_STUDIO_DB_URL", "jdbc:postgresql://127.0.0.1:1/forbidden");
    Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertNotEquals(0, process.exitValue());
      String text = Files.readString(output);
      assertTrue(text.contains("database-upgrade is unsupported in 2.0"));
      assertFalse(text.contains("Starting WebApplication"));
      assertFalse(text.contains("HikariPool"));
    } finally {
      stop(process);
    }
  }

  @Test
  void currentEmptyDatabaseStartsAndServesCatalog() throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        StartupMinioFixture minio = StartupMinioFixture.start()) {
      postgres.start();
      try (Entry entry = start(postgres, minio)) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        boolean ready = false;
        while (System.nanoTime() < deadline) {
          assertTrue(entry.process.isAlive(), "production entry exited before readiness");
          try {
            var response = entry.get("/actuator/health");
            ready = response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
            if (ready) {
              break;
            }
          } catch (IOException notReady) {
            // Connection refusal before the server binds is expected.
          }
          Thread.sleep(500);
        }
        assertTrue(ready, "production entry readiness timed out");
        var models = entry.get("/api/models");
        assertEquals(200, models.statusCode());
        assertTrue(JsonMapper.builder().build().readTree(models.body()).has("data"));
      }
    }
  }

  /** 已有数据面且 V1 与当前基线不同的库必须在 Flyway 校验处被拒绝，库内数据与结构逐字节不变。 */
  @Test
  void foreignBaselineDatabaseIsRejectedWithoutAnyChanges() throws Exception {
    Path baseline = Files.createDirectory(temp.resolve("foreign-baseline"));
    Files.writeString(
        baseline.resolve("V1__schema.sql"),
        "create table foreign_baseline (id bigint primary key);\n"
            + "insert into foreign_baseline values (1);\n");
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        StartupMinioFixture minio = StartupMinioFixture.start()) {
      postgres.start();
      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("filesystem:" + baseline)
          .load()
          .migrate();
      Map<String, String> before = snapshot(postgres);
      try (Entry entry = start(postgres, minio)) {
        assertTrue(entry.process.waitFor(120, TimeUnit.SECONDS), "rejected startup must terminate");
        assertNotEquals(0, entry.process.exitValue());
        // 生产 Logback 将应用错误写入工作目录的文件，stdout 只含启动横幅。
        assertTrue(
            Files.readString(entry.log.getParent().resolve("logs/kk-studio-all.log"))
                .contains("Migration checksum mismatch for migration version 1"),
            "must reject at migration validation");
      }
      assertEquals(before, snapshot(postgres));
    }
  }

  /** 覆盖全部 public 表（含 Flyway 历史）与结构目录的摘要。 */
  private static Map<String, String> snapshot(PostgreSQLContainer postgres) throws Exception {
    var source =
        new SimpleDriverDataSource(
            new Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Map<String, String> result = new LinkedHashMap<>();
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      List<String> tables = new ArrayList<>();
      try (ResultSet rows =
          statement.executeQuery(
              "select tablename from pg_tables where schemaname='public' order by tablename")) {
        while (rows.next()) {
          tables.add(rows.getString(1));
        }
      }
      for (String table : tables) {
        try (ResultSet rows =
            statement.executeQuery(
                "select md5(coalesce(string_agg(to_jsonb(t)::text, E'\\n' order by to_jsonb(t)::text),'')) from \""
                    + table.replace("\"", "\"\"")
                    + "\" t")) {
          rows.next();
          result.put(table, rows.getString(1));
        }
      }
      for (String catalog :
          List.of(
              "pg_class",
              "pg_attribute",
              "pg_constraint",
              "pg_index",
              "pg_namespace",
              "pg_trigger")) {
        try (ResultSet rows =
            statement.executeQuery(
                "select md5(string_agg(to_jsonb(t)::text, E'\\n' order by to_jsonb(t)::text)) from pg_catalog."
                    + catalog
                    + " t")) {
          rows.next();
          result.put("structure:" + catalog, rows.getString(1));
        }
      }
    }
    return result;
  }

  private Entry start(PostgreSQLContainer postgres, StartupMinioFixture minio) throws Exception {
    Path work = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    ProcessBuilder builder =
        new ProcessBuilder(
            command(
                "--spring.profiles.active=prod",
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--kk-studio.harness.runtime.workers-enabled=false",
                "--kk-studio.storage.maintenance.poll-delay=1h",
                "--kk-studio.plugins.refresh.poll-delay=1h",
                "--kk-studio.catalog.skill.cache-root=" + work.resolve("skills")));
    cleanEnvironment(builder);
    var env = builder.environment();
    env.put("KK_STUDIO_DB_URL", postgres.getJdbcUrl());
    env.put("KK_STUDIO_DB_USER", postgres.getUsername());
    env.put("KK_STUDIO_DB_PASSWORD", postgres.getPassword());
    env.put("KK_STUDIO_STORAGE_S3_ENDPOINT", minio.endpoint());
    env.put("KK_STUDIO_STORAGE_S3_REGION", minio.region());
    env.put("KK_STUDIO_STORAGE_S3_BUCKET", minio.bucket());
    env.put("KK_STUDIO_STORAGE_S3_ACCESS_KEY", minio.accessKey());
    env.put("KK_STUDIO_STORAGE_S3_SECRET_KEY", minio.secretKey());
    Path log = work.resolve("startup.log");
    Process process =
        builder
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      return new Entry(process, port, log);
    } catch (RuntimeException | Error failure) {
      stop(process);
      throw failure;
    }
  }

  private static List<String> command(String... args) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
    String jar = System.getProperty("kkstudio.startup.test.jar");
    if (jar != null && !jar.isBlank()) {
      assertTrue(
          Path.of(jar).isAbsolute() && Files.isRegularFile(Path.of(jar)) && jar.endsWith(".jar"));
      command.addAll(List.of("-jar", jar));
    } else {
      String original =
          System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      List<Path> paths =
          Arrays.stream(original.split(Pattern.quote(File.pathSeparator)))
              .filter(s -> !s.isBlank())
              .map(s -> Path.of(s).toAbsolutePath().normalize())
              .toList();
      List<Path> production =
          paths.stream().filter(p -> !p.endsWith(Path.of("target", "test-classes"))).toList();
      assertTrue(production.size() < paths.size());
      command.addAll(
          List.of(
              "-cp",
              production.stream()
                  .map(Path::toString)
                  .collect(Collectors.joining(File.pathSeparator)),
              WebApplication.class.getName()));
    }
    command.addAll(List.of(args));
    return command;
  }

  private static void cleanEnvironment(ProcessBuilder builder) {
    for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
      builder.environment().remove(name);
    }
  }

  private static void stop(Process process) {
    if (process.isAlive()) {
      process.destroyForcibly();
    }
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try {
      while (process.isAlive()) {
        long remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0, "child must terminate");
        try {
          process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class Entry implements AutoCloseable {
    final Process process;
    final int port;
    final Path log;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Entry(Process process, int port, Path log) {
      this.process = process;
      this.port = port;
      this.log = log;
    }

    HttpResponse<String> get(String path) throws Exception {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    public void close() throws Exception {
      try {
        http.shutdownNow();
        assertTrue(http.awaitTermination(Duration.ofSeconds(30)));
      } finally {
        stop(process);
      }
    }
  }
}
