package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lightweight architecture guard for the web module.
 *
 * <p>Production sources must not import harness packages, and {@code web/pom.xml} must not declare
 * direct harness module dependencies. Runtime/tool contracts remain available transitively through
 * Core for tests only.
 */
class WebModuleArchitectureTest {

  private static final String FORBIDDEN_IMPORT_PREFIX = "fun.fengwk.kkstudio.harness.";
  private static final List<String> FORBIDDEN_POM_ARTIFACTS =
      List.of("kk-studio-harness-runtime", "kk-studio-harness-tool", "kk-studio-harness-daemon");

  @Test
  void webMainSourcesAvoidHarnessImportsAndDirectPomDependencies() throws IOException {
    Path main = locateWebMainJava();
    assertTrue(Files.isDirectory(main), "web main sources must exist: " + main);

    List<String> importViolations = scanImportViolations(main);
    assertTrue(
        importViolations.isEmpty(),
        () -> "harness import violations:\n" + String.join("\n", importViolations));

    Path pom = locateWebPom(main);
    assertTrue(Files.isRegularFile(pom), "web/pom.xml must exist: " + pom);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    List<String> pomViolations = new ArrayList<>();
    for (String artifactId : FORBIDDEN_POM_ARTIFACTS) {
      if (pomText.contains("<artifactId>" + artifactId + "</artifactId>")) {
        pomViolations.add("direct dependency declared: " + artifactId);
      }
    }
    assertTrue(
        pomViolations.isEmpty(),
        () -> "web pom harness dependency violations:\n" + String.join("\n", pomViolations));
  }

  private static List<String> scanImportViolations(Path main) throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !isGeneratedOrTarget(path))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                      String imported = normalizeImport(trimmed);
                      if (imported.startsWith(FORBIDDEN_IMPORT_PREFIX)) {
                        violations.add(relative(main, path) + ": " + trimmed);
                      }
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    return violations;
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static boolean isGeneratedOrTarget(Path path) {
    String normalized = path.toString().replace('\\', '/');
    return normalized.contains("/target/")
        || normalized.contains("/generated-sources/")
        || normalized.contains("/generated-test-sources/");
  }

  private static String relative(Path main, Path path) {
    try {
      return main.relativize(path).toString();
    } catch (IllegalArgumentException ignored) {
      return path.getFileName().toString();
    }
  }

  private static Path locateWebMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates = List.of(cwd.resolve("src/main/java"), cwd.resolve("web/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate web main sources from " + cwd);
  }

  private static Path locateWebPom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("web/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException("cannot locate web/pom.xml from " + mainJava + " or " + cwd);
  }
}
