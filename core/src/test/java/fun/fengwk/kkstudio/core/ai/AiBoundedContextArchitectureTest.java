package fun.fengwk.kkstudio.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** 锁定定义后端 AI 限界上下文的物理包根。 */
class AiBoundedContextArchitectureTest {

  @Test
  void coreAiUsesOnlyExplicitBoundedContextRoots() throws IOException {
    // 精确的包根能暴露任何恢复出的遗留包或未审视的跨域桶。
    assertEquals(
        Set.of("catalog", "chat", "environment", "error", "runtime"),
        directDirectoryNames(
            repositoryRoot().resolve("core/src/main/java/fun/fengwk/kkstudio/core/ai")));
  }

  @Test
  void sharedContractsUseOnlyExplicitDomainRoots() throws IOException {
    // 共享根保持精确，可防止通用 share.model 包回归。
    assertEquals(
        Set.of("ai", "comfyui", "storage", "studio"),
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
