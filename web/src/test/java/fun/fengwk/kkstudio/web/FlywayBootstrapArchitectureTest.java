package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 守护生产与集成测试共用的唯一 Flyway 引导路径。 */
class FlywayBootstrapArchitectureTest {

  private static final List<String> LEGACY_RESOURCES =
      List.of(
          "core/src/main/resources/schema-postgresql.sql",
          "core/src/main/resources/data-dev-postgresql.sql",
          "core/src/main/resources/data-e2e-postgresql.sql");
  private static final List<String> FLYWAY_RESOURCES =
      List.of(
          "database/src/main/resources/db/migration/V1__schema.sql",
          "database/src/main/resources/db/seed/dev/V2__dev_seed.sql",
          "database/src/main/resources/db/seed/e2e/V2__e2e_seed.sql",
          "database/src/main/resources/db/seed/canvas-test/V3__canvas_test_system_settings.sql");
  private static final String OLD_HARNESS_SCHEMA =
      "harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/spring/"
          + "postgresql/harness-runtime-schema.sql";
  private static final List<String> BOOTSTRAP_CONFIGS =
      List.of(
          "web/src/main/resources/application-dev.yml",
          "web/src/main/resources/application-e2e.yml",
          "core/src/test/resources/application.yml",
          "web/src/test/resources/application.yml",
          "deploy/local/compose.yaml");
  private static final List<String> FORBIDDEN_BOOTSTRAPS =
      List.of("spring.sql.init", "schema-locations", "docker-entrypoint-initdb.d");
  private static final Set<String> GENERATED_DIRS =
      Set.of(".git", ".workspace", "target", "node_modules", "dist", "coverage", "reports");

  @Test
  void onlyFlywayMigrationResourcesExist() {
    Path root = repositoryRoot();
    for (String resource : LEGACY_RESOURCES) {
      assertFalse(
          Files.exists(root.resolve(resource)), "legacy resource must be absent: " + resource);
    }
    for (String resource : FLYWAY_RESOURCES) {
      assertTrue(
          Files.isRegularFile(root.resolve(resource)), "Flyway resource must exist: " + resource);
    }
    assertFalse(
        Files.exists(root.resolve(OLD_HARNESS_SCHEMA)),
        "the old runtime-spring schema mirror must be gone: " + OLD_HARNESS_SCHEMA);
  }

  @Test
  void exactlyOneBaselineMigrationExistsAcrossTheWholeRepository() throws IOException {
    Path root = repositoryRoot();
    List<Path> files = repositoryFiles(root);
    List<String> migrations =
        files.stream()
            .filter(path -> path.getFileName().toString().equals("V1__schema.sql"))
            .map(root::relativize)
            .map(Path::toString)
            .sorted()
            .toList();
    assertEquals(
        List.of("database/src/main/resources/db/migration/V1__schema.sql"),
        migrations,
        "V1 baseline must exist exactly once across the whole repository, owned by the database module");
    assertFalse(
        files.stream()
            .anyMatch(path -> path.getFileName().toString().equals("harness-runtime-schema.sql")),
        "no file in the repository may keep the old schema mirror");
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
  void webRunsWithFlywayAndCoreHarnessHasExplicitTestScope() throws IOException {
    Path root = repositoryRoot();
    String webPom = Files.readString(root.resolve("web/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(webPom.contains("<artifactId>flyway-core</artifactId>"));
    assertTrue(webPom.contains("<artifactId>flyway-database-postgresql</artifactId>"));
    assertTrue(
        webPom.contains("<artifactId>kk-studio-database</artifactId>"),
        "web must depend on the database module");

    String corePom = Files.readString(root.resolve("core/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        corePom.contains("<artifactId>flyway-core</artifactId>\n            <scope>test</scope>"));
    assertTrue(
        corePom.contains(
            "<artifactId>flyway-database-postgresql</artifactId>\n            <scope>test</scope>"));
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

  /** 路径任一段命中生成/依赖目录名（.git/.workspace/target/node_modules/dist/coverage/reports 等）时不算仓库源文件。 */
  private static boolean isRepositorySource(Path root, Path path) {
    for (Path segment : root.relativize(path)) {
      if (GENERATED_DIRS.contains(segment.toString())) {
        return false;
      }
    }
    return true;
  }

  private static Path repositoryRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.getParent())) {
      if (candidate != null
          && Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("core/src/main/java"))
          && Files.isDirectory(candidate.resolve("web/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
