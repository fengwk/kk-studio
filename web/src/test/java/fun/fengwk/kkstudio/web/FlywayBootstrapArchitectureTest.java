package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 守护生产与集成测试共用的唯一 Flyway 引导路径。 */
class FlywayBootstrapArchitectureTest {

  private static final List<String> LEGACY_RESOURCES =
      List.of(
          "core/src/main/resources/schema-postgresql.sql",
          "core/src/main/resources/data-dev-postgresql.sql",
          "core/src/main/resources/data-e2e-postgresql.sql");
  private static final List<String> FLYWAY_RESOURCES =
      List.of(
          "core/src/main/resources/db/migration/V1__schema.sql",
          "core/src/main/resources/db/seed/dev/V2__dev_seed.sql",
          "core/src/main/resources/db/seed/e2e/V2__e2e_seed.sql");
  private static final List<String> BOOTSTRAP_CONFIGS =
      List.of(
          "web/src/main/resources/application-dev.yml",
          "web/src/main/resources/application-e2e.yml",
          "core/src/test/resources/application.yml",
          "web/src/test/resources/application.yml",
          "deploy/local/compose.yaml");
  private static final List<String> FORBIDDEN_BOOTSTRAPS =
      List.of("spring.sql.init", "schema-locations", "docker-entrypoint-initdb.d");

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

    String corePom = Files.readString(root.resolve("core/pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        corePom.contains("<artifactId>flyway-core</artifactId>\n            <scope>test</scope>"));
    assertTrue(
        corePom.contains(
            "<artifactId>flyway-database-postgresql</artifactId>\n            <scope>test</scope>"));
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
