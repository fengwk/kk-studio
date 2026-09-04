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

  private static final List<String> FLYWAY_RESOURCES =
      List.of(
          "schema/src/main/resources/db/migration/V1__schema.sql",
          "schema/src/main/resources/db/seed/dev/R__dev_seed.sql",
          "schema/src/main/resources/db/seed/e2e/R__e2e_seed.sql",
          "schema/src/main/resources/db/seed/canvas-test/R__canvas_test_seed.sql");
  private static final List<String> BOOTSTRAP_CONFIGS =
      List.of(
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
  void onlyFlywayMigrationResourcesExist() throws IOException {
    Path root = repositoryRoot();
    assertTrue(Files.isDirectory(root.resolve("schema")), "schema module must exist");
    for (String resource : FLYWAY_RESOURCES) {
      assertTrue(
          Files.isRegularFile(root.resolve(resource)), "Flyway resource must exist: " + resource);
    }

    List<String> actualRepositorySqlFiles =
        repositoryFiles(root).stream()
            .map(root::relativize)
            .map(path -> path.toString().replace('\\', '/'))
            .filter(path -> path.endsWith(".sql"))
            .sorted()
            .toList();
    assertEquals(
        FLYWAY_RESOURCES.stream().sorted().toList(),
        actualRepositorySqlFiles,
        "repository sql inventory must strictly equal the four Flyway migration and seed resources");
  }

  @Test
  void exactlyOneBaselineMigrationExistsAcrossTheWholeRepository() throws IOException {
    Path root = repositoryRoot();
    List<Path> files = repositoryFiles(root);
    List<String> allVersionedMigrations =
        files.stream()
            .map(root::relativize)
            .map(path -> path.toString().replace('\\', '/'))
            .filter(
                path -> {
                  String filename = Path.of(path).getFileName().toString();
                  return filename.matches("^V.*__.*\\.sql$");
                })
            .sorted()
            .toList();
    assertEquals(
        List.of("schema/src/main/resources/db/migration/V1__schema.sql"),
        allVersionedMigrations,
        "V1 baseline must be the single versioned migration across the whole repository; no other"
            + " versioned migrations (V2+, V1_1, etc.) are allowed");
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
          && Files.isDirectory(candidate.resolve("platform/src/main/java"))
          && Files.isDirectory(candidate.resolve("web/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
