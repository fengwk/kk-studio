package fun.fengwk.kkstudio.harness.builtin;

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
 * Builtin 模块的轻量级架构守卫。
 *
 * <p>Builtin 源码只能依赖 JDK 与 {@code fun.fengwk.kkstudio.harness.contributor.api} / {@code
 * fun.fengwk.kkstudio.harness.prompt} / {@code fun.fengwk.kkstudio.harness.tool} / {@code
 * fun.fengwk.kkstudio.harness.builtin} / {@code com.fasterxml.jackson}；禁止依赖
 * runtime、Spring、infra、daemon、platform 与 web。
 */
class BuiltinModuleArchitectureTest {

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

  @Test
  void builtinMainAndTestSourcesStayOnAllowedPackages() throws IOException {
    Path main = locateBuiltinMainJava();
    assertTrue(Files.isDirectory(main), "builtin main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(),
        () -> "main architecture violations:\n" + String.join("\n", violations));

    Path test = locateBuiltinTestJava();
    assertTrue(Files.isDirectory(test), "builtin test sources must exist: " + test);

    List<String> testViolations = scanViolations(test);
    assertTrue(
        testViolations.isEmpty(),
        () -> "test architecture violations:\n" + String.join("\n", testViolations));
  }

  @Test
  void builtinPomDeclaresOnlyAllowedDependencies() throws IOException {
    Path moduleRoot = locateBuiltinMainJava().getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String scope = optionalTag(dependency, "scope");
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      if ("test".equals(scope)) {
        if (!Set.of("org.junit.jupiter:junit-jupiter", "org.mockito:mockito-core")
            .contains(coordinate)) {
          violations.add(coordinate + " [test]");
        }
        continue;
      }
      if (!Set.of(
              "fun.fengwk.kk-studio:kk-studio-harness-contributor-api",
              "fun.fengwk.kk-studio:kk-studio-harness-prompt",
              "fun.fengwk.kk-studio:kk-studio-harness-tool",
              "com.fasterxml.jackson.core:jackson-databind")
          .contains(coordinate)) {
        violations.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
      }
    }
    assertTrue(violations.isEmpty(), () -> "disallowed dependencies in " + pom + ": " + violations);
  }

  private static List<String> scanViolations(Path root) throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(root)) {
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
                        violations.add(relative(root, path) + ": " + trimmed);
                      }
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

  private static String relative(Path root, Path path) {
    try {
      return root.relativize(path).toString();
    } catch (IllegalArgumentException ignored) {
      return path.getFileName().toString();
    }
  }

  private static Path locateBuiltinMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/builtin/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate builtin main sources from " + cwd);
  }

  private static Path locateBuiltinTestJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/test/java"), cwd.resolve("harness/builtin/src/test/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate builtin test sources from " + cwd);
  }
}
