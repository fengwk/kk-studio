package fun.fengwk.kkstudio.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** 锁定 Platform 内部子域的物理包根。 */
class PlatformPackageArchitectureTest {

  @Test
  void platformUsesOnlyExplicitDomainRoots() throws IOException {
    // 精确的包根保证平台子域严格对齐当前设计。
    Path platformRoot =
        repositoryRoot().resolve("platform/src/main/java/fun/fengwk/kkstudio/platform");
    assertEquals(
        Set.of(
            "canvas",
            "catalog",
            "chat",
            "cloudfs",
            "comfyui",
            "environment",
            "error",
            "harness",
            "orchestration",
            "persistence",
            "project",
            "settings",
            "storage"),
        directDirectoryNames(platformRoot));
  }

  @Test
  void sharedContractsUseOnlyExplicitDomainRoots() throws IOException {
    // 共享根保持精确，各子域模型保持隔离。
    assertEquals(
        Set.of("ai", "canvas", "cloudfs", "comfyui", "storage", "systemsettings"),
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
      if (Files.isDirectory(candidate.resolve("platform/src/main/java"))
          && Files.isDirectory(candidate.resolve("share/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
