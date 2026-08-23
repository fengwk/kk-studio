package fun.fengwk.kkstudio.core.ai.runtime;

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

/** 守护 Core 与 framework-free Harness runtime API 的组合/应用边界。 */
class CoreHarnessArchitectureTest {

  private static final String HARNESS_INFRA = "fun.fengwk.kkstudio.harness.infra.";

  /** Core 不是组合根：main 源码和 pom 不得依赖 infra。 */
  @Test
  void coreNeverDependsOnInfra() throws IOException {
    Path main = locateCoreMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("import ") && normalizeImport(trimmed).startsWith(HARNESS_INFRA)) {
            violations.add(relative(main, path) + ": " + trimmed);
          }
        }
      }
    }

    Path pom = locateCorePom(main);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    assertFalse(
        pomText.contains("<artifactId>kk-studio-harness-infra</artifactId>"),
        "core/pom.xml must not declare kk-studio-harness-infra");

    assertTrue(
        violations.isEmpty(),
        () -> "Core infra dependency violations:\n" + String.join("\n", violations));
  }

  /**
   * path pattern 的 gitignore 语义只在 runtime 的 {@code PermissionPathPattern} 内部持有；core 永远不直接 import
   * JGit。
   */
  @Test
  void coreNeverImportsJGitDirectly() throws IOException {
    Path main = locateCoreMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("import ")
              && normalizeImport(trimmed).startsWith("org.eclipse.jgit")) {
            violations.add(relative(main, path) + ": " + trimmed);
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "Core JGit imports must not exist:\n" + String.join("\n", violations));
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
  }

  private static Path locateCoreMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(cwd.resolve("src/main/java"), cwd.resolve("core/src/main/java"))) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate core main sources from " + cwd);
  }

  private static Path locateCorePom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("core/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException("cannot locate core/pom.xml from " + mainJava + " or " + cwd);
  }
}
