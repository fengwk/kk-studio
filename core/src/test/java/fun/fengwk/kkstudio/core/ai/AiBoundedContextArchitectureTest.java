package fun.fengwk.kkstudio.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Locks the physical package roots that define the backend AI bounded contexts. */
class AiBoundedContextArchitectureTest {

  @Test
  void coreAiUsesOnlyExplicitBoundedContextRoots() throws IOException {
    // Exact roots expose any restored legacy package or unreviewed cross-domain bucket.
    assertEquals(
        Set.of("catalog", "chat", "environment", "error", "image", "runtime"),
        directDirectoryNames(
            repositoryRoot().resolve("core/src/main/java/fun/fengwk/kkstudio/core/ai")));
  }

  @Test
  void sharedContractsUseOnlyExplicitDomainRoots() throws IOException {
    // Keeping the shared root exact prevents a generic share.model package from returning.
    assertEquals(
        Set.of("ai", "api", "comfyui", "storage", "studio"),
        directDirectoryNames(
            repositoryRoot().resolve("share/src/main/java/fun/fengwk/kkstudio/share")));
  }

  private static Set<String> directDirectoryNames(Path root) throws IOException {
    try (var paths = Files.list(root)) {
      return paths
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toSet());
    }
  }

  private static Path repositoryRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : Set.of(cwd, cwd.getParent())) {
      if (Files.isDirectory(candidate.resolve("core/src/main/java"))
          && Files.isDirectory(candidate.resolve("share/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
