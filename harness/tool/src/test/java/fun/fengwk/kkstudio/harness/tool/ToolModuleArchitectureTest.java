package fun.fengwk.kkstudio.harness.tool;

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
 * Tool 模块的轻量级架构守卫。
 *
 * <p>Tool 主源码只能依赖 JDK、Jackson 和 {@code fun.fengwk.kkstudio.harness.tool}。 禁止依赖
 * Runtime/daemon/core/web、Spring/MyBatis/servlet/Redis 以及 Provider SDK。
 */
class ToolModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.daemon.",
          "fun.fengwk.kkstudio.harness.kernel.",
          "fun.fengwk.kkstudio.core.",
          "fun.fengwk.kkstudio.web.",
          "org.springframework.",
          "org.mybatis.",
          "org.apache.ibatis.",
          "jakarta.servlet.",
          "javax.servlet.",
          "jakarta.ws.",
          "javax.ws.",
          "io.lettuce.",
          "redis.clients.",
          "org.redisson.",
          "dev.langchain4j.",
          "com.openai.",
          "com.anthropic.",
          "com.google.genai.",
          "com.google.ai.");

  @Test
  void toolMainSourcesStayOnJdkJacksonAndOwnPackages() throws IOException {
    Path main = locateToolMainJava();
    assertTrue(Files.isDirectory(main), "tool main sources must exist: " + main);

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
                    if (!trimmed.startsWith("import ")) {
                      continue;
                    }
                    String imported = normalizeImport(trimmed);
                    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                      if (imported.startsWith(prefix)) {
                        violations.add(relative(main, path) + ": " + trimmed);
                      }
                    }
                    if (!isAllowedImport(imported)) {
                      violations.add(relative(main, path) + ": disallowed import " + trimmed);
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
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("com.fasterxml.jackson.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.tool.");
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

  /** Maven 从模块根目录或 reactor 根目录运行时解析 {@code src/main/java}，绝不解析到 {@code target/} 下。 */
  private static Path locateToolMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/tool/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate tool main sources from " + cwd);
  }
}
