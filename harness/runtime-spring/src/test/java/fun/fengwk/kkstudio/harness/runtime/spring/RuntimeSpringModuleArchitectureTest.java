package fun.fengwk.kkstudio.harness.runtime.spring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Guards the adapter-only source root as concrete infrastructure capabilities are introduced. */
class RuntimeSpringModuleArchitectureTest {

  private static final String PACKAGE_PREFIX = "package fun.fengwk.kkstudio.harness.runtime.spring";
  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.tool.",
          "org.postgresql.",
          "org.slf4j.",
          "org.springframework.");

  /** Every production source stays under the adapter package and imports no Core/Web technology. */
  @Test
  void mainSourcesStayInsideTheRuntimeSpringInfrastructureBoundary() throws IOException {
    Path main = locateMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(main, path, violations));
    }
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  private static void inspect(Path main, Path path, List<String> violations) {
    try {
      String source = Files.readString(path, StandardCharsets.UTF_8);
      boolean packageDeclared =
          source
              .lines()
              .map(String::trim)
              .anyMatch(line -> line.startsWith(PACKAGE_PREFIX) && line.endsWith(";"));
      if (!packageDeclared) {
        violations.add(main.relativize(path) + ": source is outside runtime.spring package");
      }
      for (String line : source.split("\\R")) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        String imported =
            trimmed.substring("import ".length()).replace(";", "").replace("static ", "").trim();
        if (ALLOWED_IMPORT_PREFIXES.stream().noneMatch(imported::startsWith)) {
          violations.add(main.relativize(path) + ": disallowed import " + trimmed);
        }
      }
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Path locateMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(
            cwd.resolve("src/main/java"), cwd.resolve("harness/runtime-spring/src/main/java"))) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate runtime-spring main sources from " + cwd);
  }
}
