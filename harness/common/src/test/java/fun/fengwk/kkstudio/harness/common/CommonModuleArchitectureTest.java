package fun.fengwk.kkstudio.harness.common;

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
 * Common 模块的轻量级架构守卫。
 *
 * <p>Common 主源码只能依赖 JDK、Jackson 和自身的 {@code fun.fengwk.kkstudio.harness.common}。 生产依赖仅允许
 * Jackson，禁止依赖任何其它 Harness 模块、平台、Spring/JDBC 或 Provider SDK。
 */
class CommonModuleArchitectureTest {

  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.tool.",
          "fun.fengwk.kkstudio.harness.environment.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.contributor.",
          "fun.fengwk.kkstudio.harness.builtin.",
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.harness.daemon.",
          "fun.fengwk.kkstudio.harness.kernel.",
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
          "dev.langchain4j.",
          "com.openai.",
          "com.anthropic.",
          "com.google.genai.",
          "com.google.ai.");

  /** 验证 Common 模块主源码只允许导入 JDK 标准库、Jackson 与自身 common 包内类型。 */
  @Test
  void commonMainSourcesStayOnJdkJacksonAndOwnPackages() throws IOException {
    Path main = locateCommonMainJava();
    assertTrue(Files.isDirectory(main), "common main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** 验证 Common 模块 POM 生产依赖仅声明 Jackson，严禁其它外部或兄弟模块依赖。 */
  @Test
  void commonPomDeclaresOnlyJacksonProductionDependency() throws IOException {
    Path moduleRoot = locateCommonMainJava().getParent().getParent().getParent();
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
        List.of("com.fasterxml.jackson.core:jackson-databind"),
        productionDependencies,
        "common module must only declare jackson-databind as production dependency");
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

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("com.fasterxml.jackson.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.common.");
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

  private static Path locateCommonMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/common/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate common main sources from " + cwd);
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
