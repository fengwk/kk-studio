package fun.fengwk.kkstudio.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** ProviderType 单一事实源的物理边界守卫。 */
class ProviderTypeArchitectureTest {

  @Test
  void shareAndPlatformCannotReintroduceProviderEnumOrMappingAdapter() throws IOException {
    Path root = repositoryRoot();
    assertTrue(
        Files.isRegularFile(
            root.resolve(
                "harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderType.java")));
    assertFalse(
        Files.exists(
            root.resolve(
                "share/src/main/java/fun/fengwk/kkstudio/share/ai/catalog/AgentProviderType.java")));

    List<String> violations = new ArrayList<>();
    for (Path sourceRoot :
        List.of(root.resolve("share/src/main/java"), root.resolve("platform/src/main/java"))) {
      try (var paths = Files.walk(sourceRoot)) {
        for (Path path :
            paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
          for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.contains("AgentProviderType") || line.contains("toProviderType(")) {
              violations.add(root.relativize(path) + ": " + line.trim());
            }
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "Catalog/platform must use runtime ProviderType directly; stale enum or mapping found:\n"
                + String.join("\n", violations));
  }

  private static Path repositoryRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.getParent())) {
      if (candidate != null
          && Files.isDirectory(candidate.resolve("platform/src/main/java"))
          && Files.isDirectory(candidate.resolve("share/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
