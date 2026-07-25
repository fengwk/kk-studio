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
import java.util.stream.Stream;

/**
 * Lightweight architecture guard for the runtime module.
 *
 * <p>Scans the entire {@code src/main/java} tree (including {@code harness.model}) and rejects
 * Spring/MyBatis/servlet/web, Provider SDK, core/web and deleted kernel dependencies.
 */
class RuntimeModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "org.springframework.",
          "org.mybatis.",
          "org.apache.ibatis.",
          "jakarta.servlet.",
          "javax.servlet.",
          "jakarta.ws.",
          "javax.ws.",
          "org.springframework.web.",
          "dev.langchain4j.",
          "com.openai.",
          "com.anthropic.",
          "com.google.genai.",
          "com.google.ai.",
          "fun.fengwk.kkstudio.core.",
          "fun.fengwk.kkstudio.web.",
          "fun.fengwk.kkstudio.harness.kernel.");

  private static final List<String> FORBIDDEN_TEXT_MARKERS =
      List.of(
          "package fun.fengwk.kkstudio.harness.kernel",
          "fun.fengwk.kkstudio.harness.kernel.",
          "kk-studio-harness-kernel");

  @Test
  void runtimeMainSourcesAvoidInfrastructureProviderSdkAndDeletedKernel() throws IOException {
    Path main = locateRuntimeMainJava();
    assertTrue(Files.isDirectory(main), "runtime main sources must exist: " + main);

    Path deletedKernelPackage = main.resolve("fun/fengwk/kkstudio/harness/kernel");
    assertFalse(
        Files.exists(deletedKernelPackage),
        "deleted harness.kernel package tree must not exist: " + deletedKernelPackage);

    Path moduleRoot = main.getParent().getParent().getParent();
    Path deletedKernelModule = moduleRoot.resolveSibling("kernel");
    assertFalse(
        Files.exists(deletedKernelModule),
        "deleted harness/kernel module directory must not exist: " + deletedKernelModule);

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
                      for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                        if (imported.startsWith(prefix)) {
                          violations.add(relative(main, path) + ": " + trimmed);
                        }
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
