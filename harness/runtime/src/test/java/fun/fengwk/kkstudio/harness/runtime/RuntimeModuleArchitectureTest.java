package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.ignore.FastIgnoreRule;
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
 * runtime 模块的轻量级架构守卫。
 *
 * <p>基于 allowlist 扫描整个 {@code src/main/java} 目录，校验全部 Harness 模块及其直接的生产依赖与 import 边界。
 */
class RuntimeModuleArchitectureTest {

  private static final String JGIT_IMPORT_PREFIX = "org.eclipse.jgit.";
  private static final String BUILTIN_IMPORT_PREFIX = "fun.fengwk.kkstudio.harness.builtin.";
  private static final String JGIT_OWNER =
      "fun/fengwk/kkstudio/harness/runtime/permission/PermissionPathPattern.java";

  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "lombok.",
          "com.fasterxml.jackson.",
          "fun.fengwk.kkstudio.harness.common.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.tool.",
          "fun.fengwk.kkstudio.harness.environment.",
          FastIgnoreRule.class.getName());

  private static final Pattern MODULE_PATTERN =
      Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  @Test
  void runtimeMainSourcesAndHarnessModulesStayWithinDeclaredBoundaries() throws IOException {
    Path main = locateRuntimeMainJava();
    assertTrue(Files.isDirectory(main), "runtime main sources must exist: " + main);

    Path runtimeModelPackage = main.resolve("fun/fengwk/kkstudio/harness/runtime/model");
    assertTrue(
        Files.isDirectory(runtimeModelPackage),
        "runtime model package tree must exist: " + runtimeModelPackage);

    Path moduleRoot = main.getParent().getParent().getParent();
    Path harnessRoot = moduleRoot.getParent();
    Path legitimateCommonPromptPackage =
        harnessRoot.resolve("common/src/main/java/fun/fengwk/kkstudio/harness/common/prompt");
    assertTrue(
        Files.isDirectory(legitimateCommonPromptPackage),
        "harness/common prompt package must exist: " + legitimateCommonPromptPackage);

    assertHarnessModules(harnessRoot.resolve("pom.xml"));
    Path rootPom = harnessRoot.getParent().resolve("pom.xml");
    assertManagedInternalDependency(rootPom, "kk-studio-harness-common");
    assertManagedInternalDependency(rootPom, "kk-studio-harness-contributor-api");
    assertManagedInternalDependency(rootPom, "kk-studio-harness-builtin");
    assertManagedInternalDependency(rootPom, "kk-studio-harness-infra");
    assertManagedInternalDependency(rootPom, "kk-studio-harness-environment");
    assertDirectProductionDependencies(
        harnessRoot.resolve("common/pom.xml"),
        Set.of("com.fasterxml.jackson.core:jackson-databind"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("tool/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-common"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("environment/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-common"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("runtime/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-common",
            "fun.fengwk.kk-studio:kk-studio-harness-tool",
            "fun.fengwk.kk-studio:kk-studio-harness-environment",
            "org.eclipse.jgit:org.eclipse.jgit",
            "org.slf4j:slf4j-api"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("contributor-api/pom.xml"),
        Set.of(
            "fun.fengwk.kk-studio:kk-studio-harness-tool",
            "fun.fengwk.kk-studio:kk-studio-harness-environment"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("builtin/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "fun.fengwk.kk-studio:kk-studio-harness-common",
            "fun.fengwk.kk-studio:kk-studio-harness-contributor-api",
            "fun.fengwk.kk-studio:kk-studio-harness-tool",
            "fun.fengwk.kk-studio:kk-studio-harness-environment"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("infra/pom.xml"),
        Set.of(
            "fun.fengwk.kk-studio:kk-studio-harness-common",
            "fun.fengwk.kk-studio:kk-studio-harness-runtime",
            "fun.fengwk.kk-studio:kk-studio-harness-tool",
            "fun.fengwk.kk-studio:kk-studio-harness-environment",
            "org.postgresql:postgresql",
            "org.springframework:spring-jdbc"));
    assertDirectProductionDependencies(
        harnessRoot.resolve("daemon/pom.xml"),
        Set.of(
            "com.fasterxml.jackson.core:jackson-databind",
            "dev.langchain4j:langchain4j-skills",
            "fun.fengwk.kk-studio:kk-studio-harness-common",
            "fun.fengwk.kk-studio:kk-studio-harness-environment",
            "org.eclipse.jgit:org.eclipse.jgit"));

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
                    if (trimmed.startsWith("import ")) {
                      String imported = normalizeImport(trimmed);
                      if (imported.startsWith(BUILTIN_IMPORT_PREFIX)) {
                        violations.add(
                            relative(main, path)
                                + ": runtime must not depend on builtin "
                                + trimmed);
                      } else if (!isAllowedImport(imported)) {
                        violations.add(relative(main, path) + ": disallowed import " + trimmed);
                      }
                      if (imported.startsWith(JGIT_IMPORT_PREFIX)
                          && !JGIT_OWNER.equals(relative(main, path))) {
                        violations.add(
                            relative(main, path)
                                + ": JGit import is owned only by "
                                + JGIT_OWNER
                                + ": "
                                + trimmed);
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

  private static boolean isAllowedImport(String imported) {
    return ALLOWED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
  }

  private static void assertHarnessModules(Path pom) throws IOException {
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = MODULE_PATTERN.matcher(text);
    List<String> modules = new ArrayList<>();
    while (matcher.find()) {
      modules.add(matcher.group(1).trim());
    }
    assertTrue(
        modules.equals(
            List.of(
                "common",
                "tool",
                "environment",
                "runtime",
                "contributor-api",
                "builtin",
                "infra",
                "daemon")),
        () ->
            "harness modules must be exactly common/tool/environment/runtime/contributor-api/builtin/infra/daemon, got "
                + modules);
  }

  private static void assertDirectProductionDependencies(Path pom, Set<String> allowed)
      throws IOException {
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String scope = optionalTag(dependency, "scope");
      if ("test".equals(scope)) {
        continue;
      }
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      if (!allowed.contains(coordinate)) {
        violations.add(coordinate + (scope == null ? "" : " [" + scope + "]"));
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "disallowed direct production dependencies in " + pom + ": " + violations);
  }

  private static void assertManagedInternalDependency(Path pom, String artifactId)
      throws IOException {
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    boolean found = false;
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if ("fun.fengwk.kk-studio".equals(optionalTag(dependency, "groupId"))
          && artifactId.equals(optionalTag(dependency, "artifactId"))) {
        assertTrue(
            "${kk-studio.version}".equals(optionalTag(dependency, "version")),
            () -> artifactId + " must use ${kk-studio.version} in dependencyManagement");
        found = true;
      }
    }
    assertTrue(found, () -> artifactId + " must be declared in root dependencyManagement");
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

  /** 在 Maven 从模块根目录或 reactor 根目录运行时定位 {@code src/main/java}；绝不会解析到 {@code target/} 之下。 */
  private static Path locateRuntimeMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/runtime/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate runtime main sources from " + cwd);
  }
}
