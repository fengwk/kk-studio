package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * daemon 模块的轻量架构守卫。
 *
 * <p>Daemon 的 main 源码只允许依赖 JDK、Jackson、JGit（{@code org.eclipse.jgit.ignore.FastIgnoreRule}）、{@code
 * harness.tool} 以及本模块自身包。 禁止引入 runtime/core/web、Spring/MyBatis/servlet/Redis 以及 Provider
 * SDK。LangChain4j 只允许出现在技能与 MCP 适配器包中： {@code dev.langchain4j.skills.*} 仅限 {@code
 * .../daemon/skill/}，{@code dev.langchain4j.*} 其余仅限 {@code .../daemon/mcp/langchain/}。
 */
class DaemonModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.runtime.",
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

  private static final String SKILL_ADAPTER_PACKAGE = "/skill/";
  private static final String MCP_LANGCHAIN_ADAPTER_PACKAGE = "/mcp/langchain/";

  @Test
  void daemonMainSourcesStayOnJdkJacksonToolAndOwnPackages() throws IOException {
    Path main = locateDaemonMainJava();
    assertTrue(Files.isDirectory(main), "daemon main sources must exist: " + main);

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
                  String relativePath = relative(main, path).replace('\\', '/');
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                      continue;
                    }
                    String imported = normalizeImport(trimmed);
                    if (isAllowedLangChain4jImport(imported, relativePath)) {
                      continue;
                    }
                    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                      if (imported.startsWith(prefix)) {
                        violations.add(relativePath + ": " + trimmed);
                      }
                    }
                    if (!isAllowedImport(imported)) {
                      violations.add(relativePath + ": disallowed import " + trimmed);
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    return violations;
  }

  /** LangChain4j 类型只允许出现在 daemon 技能/MCP 适配器包内。 */
  private static boolean isAllowedLangChain4jImport(String imported, String relativePath) {
    if (imported.startsWith("dev.langchain4j.skills.")) {
      return relativePath.contains(SKILL_ADAPTER_PACKAGE);
    }
    if (imported.startsWith("dev.langchain4j.")) {
      return relativePath.contains(MCP_LANGCHAIN_ADAPTER_PACKAGE);
    }
    return false;
  }

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("com.fasterxml.jackson.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.tool.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.daemon.")
        || imported.equals(FastIgnoreRule.class.getName());
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

  /** 当 Maven 在模块根或 reactor 根运行时解析 {@code src/main/java}；不会解析到 {@code target/} 下。 */
  private static Path locateDaemonMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/daemon/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate daemon main sources from " + cwd);
  }
}
