package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 内核内部的架构守卫；模块之间的依赖方向与契约包边界由仓库检查 {@code scripts/dev/verify/repository/check.mjs} 统一校验。 */
class RuntimeModuleArchitectureTest {

  /** JGit 只为权限路径的 gitignore 语义服务，其它代码不得借此引入 Git 能力。 */
  private static final String JGIT_OWNER =
      "fun/fengwk/kkstudio/harness/runtime/permission/PermissionPathPattern.java";

  @Test
  void manualCompactionControlDoesNotDependOnProcessorInternals() throws IOException {
    Path manualControl =
        mainJava().resolve("fun/fengwk/kkstudio/harness/runtime/ManualCompactionControl.java");
    assertTrue(Files.isRegularFile(manualControl), "manual compaction control must exist");
    String source = Files.readString(manualControl, StandardCharsets.UTF_8);
    assertFalse(
        source.contains("fun.fengwk.kkstudio.harness.runtime.processor."),
        "manual compaction control must not depend on processor internals");

    List<String> publicOperations =
        Stream.of(ThreadProcessor.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getName())
            .sorted()
            .toList();
    assertEquals(
        List.of("process"),
        publicOperations,
        "ThreadProcessor public operations must be limited to claimed THREAD work reduction");
  }

  @Test
  void jgitIsConfinedToPermissionPathPattern() throws IOException {
    Path main = mainJava();
    List<String> users;
    try (Stream<Path> files = Files.walk(main)) {
      users =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> readUnchecked(path).contains("import org.eclipse.jgit."))
              .map(path -> main.relativize(path).toString().replace('\\', '/'))
              .toList();
    }
    assertEquals(List.of(JGIT_OWNER), users);
  }

  private static String readUnchecked(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** Maven 从模块根目录或 reactor 根目录运行。 */
  private static Path mainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/runtime/src/main/java"))) {
      if (Files.isDirectory(candidate.resolve("fun/fengwk/kkstudio/harness/runtime"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate runtime main sources from " + cwd);
  }
}
