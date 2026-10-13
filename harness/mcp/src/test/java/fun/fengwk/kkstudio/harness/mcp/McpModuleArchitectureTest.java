package fun.fengwk.kkstudio.harness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MCP 模块的轻量级架构守卫。
 *
 * <p>MCP 主源码只能依赖 JDK、LangChain4j SDK、Jackson 3 与 {@code fun.fengwk.kkstudio.harness.common}；禁止依赖其它
 * Harness 模块、平台、Spring/JDBC 或 Provider SDK。
 *
 * <p>Jackson 2 core/databind 是模块内唯一的 SDK 边界例外：SDK 的 {@code McpToolResultExtractor} 签名固定接收 {@code
 * com.fasterxml.jackson.databind.JsonNode}，因此只有 {@value #JACKSON2_BOUNDARY_FILE} 允许直接导入 J2
 * core/databind；其余生产代码一律使用 Jackson 3（{@code tools.jackson}）或 Jackson 3 仍复用的 {@code
 * com.fasterxml.jackson.annotation}。
 */
class McpModuleArchitectureTest {

  /** 允许直接使用 Jackson 2 core/databind 的唯一生产文件。 */
  private static final String JACKSON2_BOUNDARY_FILE = "McpResultExtractor.java";

  /** 从 POM 中提取 &lt;dependency&gt; 片段，用于断言生产依赖集合。 */
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.tool.",
          "fun.fengwk.kkstudio.harness.environment.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.daemon.",
          "fun.fengwk.kkstudio.harness.kernel.",
          "fun.fengwk.kkstudio.harness.builtin.",
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.harness.contributor.",
          "fun.fengwk.kkstudio.platform.",
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
          "com.openai.",
          "com.anthropic.",
          "com.google.genai.",
          "com.google.ai.");

  /** 验证 MCP 主源码保持在 JDK、SDK、Jackson 3 与自身/公共包边界内。 */
  @Test
  void mcpMainSourcesStayOnJdkSdkJackson3AndOwnPackages() throws IOException {
    Path main = locateMcpMainJava();
    assertTrue(Files.isDirectory(main), "mcp main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** 验证 MCP 模块生产依赖只声明 SDK、harness-common 与 Jackson 2/3 databind，扩增依赖必须先落在这里。 */
  @Test
  void mcpPomDeclaresOnlySdkCommonAndJacksonDependencies() throws IOException {
    Path moduleRoot = locateMcpMainJava().getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    List<String> productionDependencies = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String scope = optionalTag(dependency, "scope");
      if ("test".equals(scope)) {
        continue;
      }
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      productionDependencies.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
    }
    assertEquals(
        List.of(
            "dev.langchain4j:langchain4j-mcp",
            "fun.fengwk.kk-studio:kk-studio-harness-common",
            "tools.jackson.core:jackson-databind",
            "com.fasterxml.jackson.core:jackson-databind"),
        productionDependencies,
        "mcp module must declare only the SDK, harness-common and Jackson databind as production"
            + " dependencies");
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
                  String fileName = path.getFileName().toString();
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                      continue;
                    }
                    String imported = normalizeImport(trimmed);
                    if (isJackson2CoreOrDatabind(imported)) {
                      if (!JACKSON2_BOUNDARY_FILE.equals(fileName)) {
                        violations.add(
                            relative(main, path)
                                + ": Jackson 2 core/databind only allowed in "
                                + JACKSON2_BOUNDARY_FILE
                                + ": "
                                + trimmed);
                      }
                      continue;
                    }
                    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                      if (imported.startsWith(prefix)) {
                        violations.add(relative(main, path) + ": forbidden " + trimmed);
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

  /**
   * Jackson 2 core/databind 是 SDK 边界专用；Jackson 3 复用的 {@code com.fasterxml.jackson.annotation} 不算。
   */
  private static boolean isJackson2CoreOrDatabind(String imported) {
    return imported.startsWith("com.fasterxml.jackson.core.")
        || imported.startsWith("com.fasterxml.jackson.databind.");
  }

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("dev.langchain4j.")
        || imported.startsWith("com.fasterxml.jackson.annotation.")
        || imported.startsWith("tools.jackson.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.common.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.mcp.");
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
  private static Path locateMcpMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/mcp/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate mcp main sources from " + cwd);
  }

  private static String optionalTag(String xmlSnippet, String tag) {
    Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">").matcher(xmlSnippet);
    return matcher.find() ? matcher.group(1).trim() : null;
  }

  private static String requiredTag(String xmlSnippet, String tag) {
    String value = optionalTag(xmlSnippet, tag);
    if (value == null) {
      throw new IllegalArgumentException("missing <" + tag + "> tag in " + xmlSnippet);
    }
    return value;
  }
}
