package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;

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
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 只走生产入口：一次性 PostgreSQL 17 与私有 MinIO，不发起任何上游调用。 */
class WebApplicationStartupSubprocessTest {
  private static final String PASSWORD_ENV = "KK_STUDIO_BOOTSTRAP_ADMIN_PASSWORD";

  @TempDir Path temp;

  @Test
  void bootstrapPasswordIsRequiredAndSharedAcrossConcurrentStartsWithoutOverwriting()
      throws Exception {
    try (PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine")
                .withPassword(UUID.randomUUID().toString());
        StartupMinioFixture minio = StartupMinioFixture.start()) {
      postgres.start();
      try (Entry mixed = start(postgres, minio, "prod,dev", null)) {
        mixed.awaitRejection("The prod profile cannot be combined");
      }
      assertRejected(postgres, minio, null);
      assertRejected(postgres, minio, "kkstudio");
      assertEquals(0, adminCount(postgres));

      String approvedPassword = "test-bootstrap-" + UUID.randomUUID();
      // Start both nodes before waiting for readiness against the same empty account table.
      try (Entry first = start(postgres, minio, approvedPassword);
          Entry second = start(postgres, minio, approvedPassword)) {
        first.awaitReady();
        second.awaitReady();
        assertEquals(401, first.get("/api/platform/models").statusCode());
        first.loginAsBootstrapAdmin(approvedPassword);
        second.loginAsBootstrapAdmin(approvedPassword);
        assertEquals(1, adminCount(postgres));
        assertFalse(first.startupOutput().contains(approvedPassword));
        assertFalse(second.startupOutput().contains(approvedPassword));
      }

      try (Entry restarted = start(postgres, minio, null)) {
        restarted.awaitReady();
        restarted.loginAsBootstrapAdmin(approvedPassword);
        assertEquals(1, adminCount(postgres));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "e2e", "dev,canvas-test"})
  void disposableProfilesKeepTheirExplicitLoginFixture(String profiles) throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        StartupMinioFixture minio = StartupMinioFixture.start()) {
      postgres.start();
      try (Entry entry = start(postgres, minio, profiles, null)) {
        entry.awaitReady();
        entry.loginAsBootstrapAdmin("kkstudio");
      }
    }
  }

  private void assertRejected(
      PostgreSQLContainer postgres, StartupMinioFixture minio, String password) throws Exception {
    try (Entry entry = start(postgres, minio, password)) {
      String output = entry.awaitRejection(PASSWORD_ENV);
      assertFalse(
          output.contains(postgres.getPassword()),
          "startup output must not expose database credentials");
      assertTrue(
          output.contains(
              password == null
                  ? "initialize the first Studio administrator"
                  : "disposable test password"));
    }
  }

  private long adminCount(PostgreSQLContainer postgres) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement();
        var result =
            statement.executeQuery("select count(*) from studio_user where role = 'ADMIN'")) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private Entry start(PostgreSQLContainer postgres, StartupMinioFixture minio, String password)
      throws Exception {
    return start(postgres, minio, "prod", password);
  }

  private Entry start(
      PostgreSQLContainer postgres, StartupMinioFixture minio, String profiles, String password)
      throws Exception {
    Path work = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    ProcessBuilder builder =
        new ProcessBuilder(
            command(
                "--spring.profiles.active=" + profiles,
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--kk-studio.harness.runtime.workers-enabled=false",
                "--kk-studio.storage.maintenance.poll-delay=1h",
                "--kk-studio.plugins.refresh.poll-delay=1h"));
    // Never pass through operator or test-runner secrets into the production subprocess.
    builder.environment().clear();
    var env = builder.environment();
    env.put("KK_STUDIO_DB_URL", postgres.getJdbcUrl());
    env.put("KK_STUDIO_DB_USER", postgres.getUsername());
    env.put("KK_STUDIO_DB_PASSWORD", postgres.getPassword());
    env.put("KK_STUDIO_STORAGE_S3_ENDPOINT", minio.endpoint());
    env.put("KK_STUDIO_STORAGE_S3_REGION", minio.region());
    env.put("KK_STUDIO_STORAGE_S3_BUCKET", minio.bucket());
    env.put("KK_STUDIO_STORAGE_S3_ACCESS_KEY", minio.accessKey());
    env.put("KK_STUDIO_STORAGE_S3_SECRET_KEY", minio.secretKey());
    if (password != null) {
      env.put(PASSWORD_ENV, password);
    }
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

    void awaitReady() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      while (System.nanoTime() < deadline) {
        assertTrue(process.isAlive(), "production entry exited before readiness");
        try {
          var response = get("/actuator/health");
          if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
            return;
          }
        } catch (IOException notReady) {
          // Connection refusal before the server binds is expected.
        }
        Thread.sleep(500);
      }
      throw new AssertionError("production entry readiness timed out");
    }

    String awaitRejection(String expectedDiagnostic) throws Exception {
      assertTrue(process.waitFor(45, TimeUnit.SECONDS), "invalid startup configuration must exit");
      assertFalse(process.exitValue() == 0, "invalid startup configuration must be rejected");
      String output = startupOutput();
      assertTrue(output.contains(expectedDiagnostic), "expected diagnostic: " + expectedDiagnostic);
      return output;
    }

    String startupOutput() throws IOException {
      Path applicationLog = log.resolveSibling("logs").resolve("kk-studio-all.log");
      return Files.readString(log)
          + (Files.exists(applicationLog) ? Files.readString(applicationLog) : "");
    }

    void loginAsBootstrapAdmin(String password) throws Exception {
      var login =
          send(
              request("/api/auth/login")
                  .header("Origin", origin())
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"username\":\"kkstudio\",\"password\":\"" + password + "\"}")));
      assertEquals(200, login.statusCode());
      String cookie =
          login.headers().allValues("Set-Cookie").stream()
              .map(value -> value.split(";", 2)[0])
              .filter(value -> value.startsWith(StudioBrowserSession.COOKIE + "="))
              .findFirst()
              .orElseThrow();
      var models = send(request("/api/platform/models").header("Cookie", cookie).GET());
      assertEquals(200, models.statusCode());
      assertTrue(JsonMapper.builder().build().readTree(models.body()).isArray());
    }

    String origin() {
      return "http://127.0.0.1:" + port;
    }

    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(origin() + path)).timeout(Duration.ofSeconds(5));
    }

    HttpResponse<String> get(String path) throws Exception {
      return send(request(path).GET());
    }

    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
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
