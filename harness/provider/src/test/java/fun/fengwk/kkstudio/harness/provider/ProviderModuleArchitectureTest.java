package fun.fengwk.kkstudio.harness.provider;

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
 * provider 模块的架构守卫。
 *
 * <p>校验模块生产源码无 Spring/LangChain4j/Reactor/OkHttp/Apache HTTP 依赖与 import，直接生产依赖仅声明 harness-runtime。
 */
class ProviderModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "org.springframework.",
          "dev.langchain4j.",
          "io.projectreactor.",
          "reactor.",
          "okhttp3.",
          "org.apache.hc.",
          "org.apache.http.",
          "com.github.tomakehurst.wiremock.");

  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "lombok.",
          "org.slf4j.",
          "com.fasterxml.jackson.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.provider.");

  @Test
  void providerMainSourcesStayWithinAllowedBoundaries() throws IOException {
    Path main = locateProviderMainJava();
    assertTrue(Files.isDirectory(main), "provider main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(),
        () -> "provider architecture violations:\n" + String.join("\n", violations));
  }

  @Test
  void providerPomDeclaresOnlyRuntimeAsDirectProductionDependency() throws IOException {
    Path main = locateProviderMainJava();
    Path moduleRoot = main.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String block = matcher.group(1);
      String scope = optionalTag(block, "scope");
      if ("test".equals(scope)) {
        continue;
      }
      String coordinate = requiredTag(block, "groupId") + ":" + requiredTag(block, "artifactId");
      if (!Set.of(
              "fun.fengwk.kk-studio:kk-studio-harness-runtime",
              "com.fasterxml.jackson.core:jackson-databind")
          .contains(coordinate)) {
        violations.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "disallowed direct production dependencies in provider pom: " + violations);
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
                    for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                      if (imported.startsWith(forbidden)) {
                        violations.add(relative(main, path) + ": forbidden import: " + trimmed);
                      }
                    }
                    if (ALLOWED_IMPORT_PREFIXES.stream().noneMatch(imported::startsWith)) {
                      violations.add(relative(main, path) + ": disallowed import: " + trimmed);
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    return violations;
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

  private static Path locateProviderMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/provider/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate provider main sources from " + cwd);
  }
}
