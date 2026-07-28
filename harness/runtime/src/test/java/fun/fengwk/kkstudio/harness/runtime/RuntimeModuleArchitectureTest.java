package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lightweight architecture guard for the runtime module.
 *
 * <p>Scans the entire {@code src/main/java} tree against an allowlist, and verifies the three
 * Harness modules plus their direct production dependencies. The former top-level model package has
 * been folded into {@code fun.fengwk.kkstudio.harness.runtime.model}; the old source directory must
 * not reappear.
 */
class RuntimeModuleArchitectureTest {

  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "lombok.",
          "com.fasterxml.jackson.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.tool.");

  private static final List<String> FORBIDDEN_TEXT_MARKERS =
      List.of(
          "package fun.fengwk.kkstudio.harness.kernel",
          "fun.fengwk.kkstudio.harness.kernel.",
          "kk-studio-harness-kernel");

  private static final Pattern MODULE_PATTERN =
      Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  @Test
  void runtimeMainSourcesAndHarnessModulesStayWithinDeclaredBoundaries() throws IOException {
    Path main = locateRuntimeMainJava();
    assertTrue(Files.isDirectory(main), "runtime main sources must exist: " + main);

    Path runtimeModelPackage = main.resolve("fun/fengwk/kkstudio/harness/runtime/model");
    assertTrue(
        Files.isDirectory(runtimeModelPackage),
        "runtime model package tree must exist: " + runtimeModelPackage);

    Path deletedLegacyModelPackage = main.resolve("fun/fengwk/kkstudio/harness/model");
    assertFalse(
        Files.exists(deletedLegacyModelPackage),
        "deleted legacy model package tree must not exist: " + deletedLegacyModelPackage);

    Path deletedKernelPackage = main.resolve("fun/fengwk/kkstudio/harness/kernel");
    assertFalse(
        Files.exists(deletedKernelPackage),
        "deleted harness.kernel package tree must not exist: " + deletedKernelPackage);

    Path moduleRoot = main.getParent().getParent().getParent();
    Path deletedKernelModule = moduleRoot.resolveSibling("kernel");
    assertFalse(
        Files.exists(deletedKernelModule),
        "deleted harness/kernel module directory must not exist: " + deletedKernelModule);

    Path harnessRoot = moduleRoot.getParent();
    assertHarnessModules(harnessRoot.resolve("pom.xml"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("tool/pom.xml"), Set.of("com.fasterxml.jackson.core:jackson-databind"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("runtime/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-tool",
            "org.slf4j:slf4j-api"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("daemon/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-tool"));

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  private static List<String> scanViolations(Path main) throws IOException {
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
                      if (!isAllowedImport(imported)) {
                        violations.add(relative(main, path) + ": disallowed import " + trimmed);
                      }
                    }
                    for (String marker : FORBIDDEN_TEXT_MARKERS) {
                      if (trimmed.contains(marker)) {
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

  private static boolean isAllowedImport(String imported) {
    return ALLOWED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
  }

  private static void assertHarnessModules(Path pom) throws IOException {
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = MODULE_PATTERN.matcher(text);
    List<String> modules = new ArrayList<>();
    while (matcher.find()) {
      modules.add(matcher.group(1).trim());
    }
    assertTrue(
        modules.equals(List.of("tool", "runtime", "daemon")),
        () -> "harness modules must be exactly tool/runtime/daemon, got " + modules);
  }

  private static void assertDirectProductionDependencies(Path pom, Set<String> allowed)
      throws IOException {
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String scope = optionalTag(dependency, "scope");
      if ("test".equals(scope)) {
        continue;
      }
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      if (!allowed.contains(coordinate)) {
        violations.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "disallowed direct production dependencies in " + pom + ": " + violations);
  }

  private static String requiredTag(String block, String tag) {
    String value = optionalTag(block, tag);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("dependency must declare " + tag);
    }
    return value;
  }

  private static String optionalTag(String block, String tag) {
    Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(block);
    return matcher.find() ? matcher.group(1).trim() : null;
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

  /**
   * Resolves {@code src/main/java} when Maven runs from the module root or the reactor root. Never
   * resolves under {@code target/}.
   */
  private static Path locateRuntimeMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/runtime/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate runtime main sources from " + cwd);
  }
}
