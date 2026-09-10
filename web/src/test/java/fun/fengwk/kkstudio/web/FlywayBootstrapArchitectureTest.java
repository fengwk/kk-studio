package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 守护生产与集成测试共用的唯一 Flyway 引导路径、冻结的 V1 baseline 和 prod 凭据边界。 */
class FlywayBootstrapArchitectureTest {

  private static final String MIGRATION_DIR = "schema/src/main/resources/db/migration";
  private static final String BASELINE_MIGRATION = MIGRATION_DIR + "/V1__schema.sql";
  private static final String PROD_CONFIG = "web/src/main/resources/application-prod.yml";

  /**
   * V1 已经应用到 Main/Dev 共享 database，因此它是不可变事实而不是可迭代草稿：任何 schema 变更只能新增 V2+ migration。该摘要让"修改历史
   * baseline"在 CI 立即失败，避免两个节点之间出现 schema 漂移。
   */
  private static final String BASELINE_SHA256 =
      "4a87857c61fb5b42e004773434d327ba87cbabb62c36a61c98a1ce79fc4fdc4b";

  /** 每个 profile seed 都必须显式落在这份清单内；新增 seed 需要同时更新本测试和 bootstrap 配置。 */
  private static final List<String> SEED_RESOURCES =
      List.of(
          "schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql",
          "schema/src/main/resources/db/seed/dev/R__dev_seed.sql",
          "schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql");

  private static final Pattern VERSIONED_MIGRATION =
      Pattern.compile("^V(?<version>\\d+)__(?<description>[A-Za-z0-9_]+)\\.sql$");
  private static final Pattern REPEATABLE_SEED = Pattern.compile("^R__[a-z0-9_]+\\.sql$");
  private static final List<String> BOOTSTRAP_CONFIGS =
      List.of(
          PROD_CONFIG,
          "web/src/main/resources/application-dev.yml",
          "web/src/main/resources/application-e2e.yml",
          "platform/src/test/resources/application.yml",
          "web/src/test/resources/application.yml",
          "deploy/local/compose.yaml");
  private static final List<String> FORBIDDEN_BOOTSTRAPS =
      List.of("spring.sql.init", "schema-locations", "docker-entrypoint-initdb.d");
  private static final Set<String> GENERATED_DIRS =
      Set.of(".git", ".workspace", "target", "node_modules", "dist", "coverage", "reports");

  @Test
  void onlySchemaModuleOwnsFlywaySqlResources() throws IOException {
    Path root = repositoryRoot();
    assertTrue(
        Files.isDirectory(root.resolve(MIGRATION_DIR)), "schema migration directory must exist");

    List<String> repositorySqlFiles =
        repositoryFiles(root).stream()
            .map(root::relativize)
            .map(path -> path.toString().replace('\\', '/'))
            .filter(path -> path.endsWith(".sql"))
            .sorted()
            .toList();
    List<String> migrationFiles =
        repositorySqlFiles.stream().filter(path -> path.startsWith(MIGRATION_DIR + "/")).toList();
    List<String> otherSqlFiles =
        repositorySqlFiles.stream().filter(path -> !path.startsWith(MIGRATION_DIR + "/")).toList();

    assertTrue(migrationFiles.contains(BASELINE_MIGRATION), "V1 baseline must exist");
    for (String migration : migrationFiles) {
      String filename = Path.of(migration).getFileName().toString();
      assertTrue(
          VERSIONED_MIGRATION.matcher(filename).matches(),
          () -> "migration must stay well named as V<version>__<description>.sql: " + migration);
    }

    assertEquals(
        SEED_RESOURCES,
        otherSqlFiles,
        "every non-migration sql file must be one of the three constrained profile seeds");
    for (String seed : otherSqlFiles) {
      String filename = Path.of(seed).getFileName().toString();
      assertTrue(
          REPEATABLE_SEED.matcher(filename).matches(),
          () -> "profile seed must stay a repeatable migration: " + seed);
    }
  }

  @Test
  void baselineMigrationIsFrozenAndIncrementalVersionsAreUnique() throws IOException {
    Path root = repositoryRoot();
    Path baseline = root.resolve(BASELINE_MIGRATION);
    assertTrue(Files.isRegularFile(baseline), "V1 baseline must exist");
    assertEquals(
        BASELINE_SHA256,
        sha256(baseline),
        "V1 is already applied to the shared database and must not change; express schema changes as"
            + " new V2+ migrations");

    List<Integer> versions = versionedMigrationVersions(root);
    assertTrue(versions.contains(1), "V1 baseline must exist");
    List<Integer> incrementVersions = versions.stream().filter(version -> version != 1).toList();
    for (int version : incrementVersions) {
      assertTrue(version >= 2, () -> "only V2+ migrations may be added; found version V" + version);
    }
    assertEquals(
        incrementVersions.size(),
        new TreeSet<>(incrementVersions).size(),
        "each increment migration version must be unique");
  }

  @Test
  void productionProfileUsesMigrationOnlyAndEnvironmentCredentials() throws IOException {
    Path root = repositoryRoot();
    List<String> lines =
        Files.readAllLines(root.resolve(PROD_CONFIG), StandardCharsets.UTF_8).stream()
            .map(String::strip)
            .toList();

    assertTrue(
        lines.contains("locations: classpath:db/migration"),
        "production Flyway locations must be exactly classpath:db/migration");
    assertFalse(
        lines.stream()
            .anyMatch(line -> !line.startsWith("#") && line.toLowerCase().contains("seed")),
        "production profile must not activate any profile seed path");
    for (String property :
        List.of(
            "url: ${KK_STUDIO_DB_URL}",
            "username: ${KK_STUDIO_DB_USER}",
            "password: ${KK_STUDIO_DB_PASSWORD}")) {
      assertTrue(
          lines.contains(property),
          () ->
              "production datasource must be environment-only without credential defaults: "
                  + property);
    }
    assertTrue(
        lines.contains("forward-headers-strategy: framework"),
        "production must restore external scheme/host from the Gateway X-Forwarded-* headers");
  }

  @Test
  void configsAndComposeDoNotRestoreCompetingBootstrap() throws IOException {
    Path root = repositoryRoot();
    for (String config : BOOTSTRAP_CONFIGS) {
      String text = Files.readString(root.resolve(config), StandardCharsets.UTF_8);
      for (String forbidden : FORBIDDEN_BOOTSTRAPS) {
        assertFalse(text.contains(forbidden), () -> config + " must not configure " + forbidden);
      }
    }
  }

  @Test
  void schemaModuleIsWiredWithRequiredScopes() throws IOException {
    Path root = repositoryRoot();
    String rootPom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
    assertTrue(rootPom.contains("<module>schema</module>"));
    assertTrue(rootPom.contains("<artifactId>kk-studio-schema</artifactId>"));

    String webPom = Files.readString(root.resolve("web/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        webPom.contains("<artifactId>spring-boot-starter-flyway</artifactId>"),
        "web must declare spring-boot-starter-flyway to activate Boot 4 Flyway auto-configuration");
    assertTrue(
        webPom.contains("<artifactId>flyway-database-postgresql</artifactId>"),
        "web must declare flyway-database-postgresql for PostgreSQL database support");
    assertFalse(
        webPom.contains("<artifactId>flyway-core</artifactId>"),
        "web must not declare flyway-core directly; the starter already transitively provides it");
    assertTrue(
        webPom.contains(
            "<artifactId>kk-studio-schema</artifactId>\n            <scope>runtime</scope>"),
        "web must depend on the schema module at runtime");

    String platformPom = Files.readString(root.resolve("platform/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        platformPom.contains(
            "<artifactId>kk-studio-schema</artifactId>\n            <scope>test</scope>"),
        "platform must depend on the schema module for infrastructure tests");
    assertTrue(
        platformPom.contains(
            "<artifactId>flyway-core</artifactId>\n            <scope>test</scope>"));
    assertTrue(
        platformPom.contains(
            "<artifactId>flyway-database-postgresql</artifactId>\n            <scope>test</scope>"));

    String harnessInfraPom =
        Files.readString(root.resolve("harness/infra/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        harnessInfraPom.contains(
            "<artifactId>kk-studio-schema</artifactId>\n            <scope>test</scope>"),
        "harness infra must depend on the schema module for infrastructure tests");
  }

  /** 一次性物化整个仓库的常规文件（跳过生成/依赖目录），Stream 在方法内关闭。 */
  private static List<Path> repositoryFiles(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> isRepositorySource(root, path))
          .toList();
    }
  }

  /** 路径任一段命中生成或依赖目录名（.git/.workspace/target/node_modules/dist/coverage/reports 等）时不算源码。 */
  private static boolean isRepositorySource(Path root, Path path) {
    for (Path segment : root.relativize(path)) {
      if (GENERATED_DIRS.contains(segment.toString())) {
        return false;
      }
    }
    return true;
  }

  /** 收集 schema module 中全部 versioned migration 的数值版本（保留重复项），repeatable seed 不参与。 */
  private static List<Integer> versionedMigrationVersions(Path root) throws IOException {
    Path migrationDir = root.resolve(MIGRATION_DIR);
    List<Integer> versions = new ArrayList<>();
    try (Stream<Path> migrations = Files.list(migrationDir)) {
      for (Path migration : migrations.toList()) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(migration.getFileName().toString());
        assertTrue(
            matcher.matches(),
            () -> "migration must stay well named as V<version>__<description>.sql: " + migration);
        versions.add(Integer.parseInt(matcher.group("version")));
      }
    }
    return versions;
  }

  private static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available", e);
    }
  }

  private static Path repositoryRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.getParent())) {
      if (candidate != null
          && Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("platform/src/main/java"))
          && Files.isDirectory(candidate.resolve("web/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
