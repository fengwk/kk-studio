package fun.fengwk.kkstudio.core.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Guards the Core composition/application boundary around framework-free Harness runtime APIs. */
class CoreHarnessArchitectureTest {

  private static final String HARNESS_RUNTIME = "fun.fengwk.kkstudio.harness.runtime.";
  private static final String CORE = "fun.fengwk.kkstudio.core.";

  private static final List<String> COMMAND_BOUNDARY_FORBIDDEN_IMPORTS =
      List.of(
          HARNESS_RUNTIME + "configuration." + "RuntimeConfigSource",
          HARNESS_RUNTIME + "thread." + "ThreadCommandTransactions");

  private static final List<String> INTERACTION_BOUNDARY_FORBIDDEN_IMPORTS =
      List.of(
          HARNESS_RUNTIME + "interaction." + "InteractionHandler",
          HARNESS_RUNTIME + "interaction." + "InteractionHandlerRegistry",
          HARNESS_RUNTIME + "interaction." + "InteractionTransactions");

  @Test
  void applicationBoundariesConsumeRuntimeApiWithoutOutboundSpiOrConcreteGatewayCoupling()
      throws IOException {
    Path main = locateCoreMainJava();
    List<String> violations = new ArrayList<>();

    scanTree(
        main.resolve("fun/fengwk/kkstudio/core/harness/thread/service"),
        COMMAND_BOUNDARY_FORBIDDEN_IMPORTS,
        violations);
    scanTree(
        main.resolve("fun/fengwk/kkstudio/core/harness/session/service"),
        COMMAND_BOUNDARY_FORBIDDEN_IMPORTS,
        violations);
    scanFile(
        main.resolve(
            "fun/fengwk/kkstudio/core/harness/interaction/service/InteractionService.java"),
        INTERACTION_BOUNDARY_FORBIDDEN_IMPORTS,
        violations);
    scanTree(
        main.resolve("fun/fengwk/kkstudio/core/harness/interaction/service/impl"),
        INTERACTION_BOUNDARY_FORBIDDEN_IMPORTS,
        violations);
    scanFile(
        main.resolve(
            "fun/fengwk/kkstudio/core/harness/tool/worker/HarnessToolWorkerConfiguration.java"),
        List.of(CORE + "environment.gateway." + "EnvironmentDaemonGateway"),
        violations);
    scanFile(
        main.resolve("fun/fengwk/kkstudio/core/environment/gateway/EnvironmentDaemonGateway.java"),
        List.of(HARNESS_RUNTIME + "tool.worker." + "ToolWorker"),
        violations);

    assertTrue(
        violations.isEmpty(),
        () -> "Core Harness boundary violations:\n" + String.join("\n", violations));
  }

  private static void scanTree(Path root, List<String> forbidden, List<String> violations)
      throws IOException {
    assertTrue(Files.isDirectory(root), "source directory must exist: " + root);
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
        scanFile(path, forbidden, violations);
      }
    }
  }

  private static void scanFile(Path path, List<String> forbidden, List<String> violations)
      throws IOException {
    assertTrue(Files.isRegularFile(path), "source file must exist: " + path);
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("import ")) {
        continue;
      }
      String imported = normalizeImport(trimmed);
      for (String forbiddenImport : forbidden) {
        if (imported.equals(forbiddenImport) || imported.startsWith(forbiddenImport + ".")) {
          violations.add(path.getFileName() + ": " + trimmed);
        }
      }
    }
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
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
}
