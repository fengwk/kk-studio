package fun.fengwk.kkstudio.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Import whitelist keeps the notification runtime below domains, and Share transport-free. */
class NotificationArchitectureTest {
  @Test
  void runtimeImportsOnlyShareAndItsActualInfrastructureDependencies() throws IOException {
    assertImports(
        Path.of("src/main/java"),
        List.of(
            "java.",
            "javax.sql.",
            "lombok.",
            "org.springframework.jdbc.",
            "org.springframework.transaction.",
            "org.postgresql.",
            "fun.fengwk.kkstudio.share.notification."));
  }

  @Test
  void publicApiDoesNotImportSpringPgOrDomains() throws IOException {
    assertImports(
        Path.of("../share/src/main/java/fun/fengwk/kkstudio/share/notification"), List.of("java."));
  }

  private static void assertImports(Path directory, List<String> allowed) throws IOException {
    try (var files = Files.walk(directory)) {
      for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(source)) {
          if (line.startsWith("import ")) {
            String imported = line.substring("import ".length());
            assertTrue(
                allowed.stream().anyMatch(imported::startsWith),
                () -> "disallowed import in " + source + ": " + imported);
          }
        }
      }
    }
  }
}
