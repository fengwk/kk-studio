package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Contributor API 模块的轻量级架构守卫。
 *
 * <p>Contributor API 主源码只能依赖 JDK 与自身公共包及 {@code fun.fengwk.kkstudio.harness.tool} / {@code
 * fun.fengwk.kkstudio.harness.environment}；严禁依赖任何 Runtime（{@code
 * fun.fengwk.kkstudio.harness.runtime}）、Platform、Daemon、Web 或第三方框架。
 */
class ContributorApiModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.harness.daemon.",
          "fun.fengwk.kkstudio.platform.",
          "fun.fengwk.kkstudio.web.",
          "org.springframework.",
          "jakarta.",
          "javax.sql.",
          "org.mybatis.",
          "org.apache.ibatis.");

  /** 验证 Contributor API 主源码仅依赖 JDK、contributor-api 自有包和 harness-tool / environment。 */
  @Test
  void contributorApiMainSourcesStayOnJdkAndHarnessApiPackages() throws IOException {
    Path main = locateContributorMainJava();
    assertTrue(Files.isDirectory(main), "contributor main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** 验证 Contributor API POM 仅声明 harness-tool / environment 为直接生产依赖，不引入 runtime 或直接 common 生产依赖。 */
  @Test
  void contributorApiPomDeclaresOnlyToolAndEnvironmentAsProductionDependencies()
      throws IOException {
    Path moduleRoot = locateContributorMainJava().getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String scope = optionalTag(dependency, "scope");
      if ("test".equals(scope)) {
        continue;
      }
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      if (!Set.of(
              "fun.fengwk.kk-studio:kk-studio-harness-tool",
              "fun.fengwk.kk-studio:kk-studio-harness-environment")
          .contains(coordinate)) {
        violations.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "disallowed direct production dependencies in " + pom + ": " + violations);
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
        || imported.startsWith("fun.fengwk.kkstudio.harness.contributor.api.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.tool.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.environment.");
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

  private static Path locateContributorMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(
            cwd.resolve("src/main/java"), cwd.resolve("harness/contributor-api/src/main/java"))) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate contributor-api main sources from " + cwd);
  }

  private static String requiredTag(String block, String tag) {
    String value = optionalTag(block, tag);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("dependency must declare " + tag);
    }
    return value;
  }

  private static String optionalTag(String block, String tag) {
    Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(block);
    return matcher.find() ? matcher.group(1).trim() : null;
  }
}
