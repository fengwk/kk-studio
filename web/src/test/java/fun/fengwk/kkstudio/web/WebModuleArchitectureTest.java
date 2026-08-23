package fun.fengwk.kkstudio.web;

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
 * web 模块的轻量架构守护。
 *
 * <p>生产源码不得引用 Harness Tool/Daemon 包或选定的 Core 基础设施实现。web 模块是 Harness 组合根：它按设计将 Runtime 契约与 infra
 * 传输适配器声明为直接依赖，且 {@code web/pom.xml} 必须保留该声明。
 */
class WebModuleArchitectureTest {

  private static final String HARNESS_RUNTIME_PREFIX = "fun.fengwk.kkstudio.harness.runtime.";
  private static final String HARNESS_INFRA_PREFIX = "fun.fengwk.kkstudio.harness.infra.";
  private static final String GOAL_PLUGIN_PREFIX = "fun.fengwk.kkstudio.harness.plugins.goal.";
  private static final String HARNESS_TOOL_PREFIX = "fun.fengwk.kkstudio.harness.tool.";
  private static final String PLATFORM_AI_REFERENCE_PREFIX = "fun.fengwk.kkstudio.platform.ai.";
  private static final List<String> ALLOWED_HARNESS_PACKAGE_PREFIXES =
      List.of(HARNESS_RUNTIME_PREFIX, HARNESS_INFRA_PREFIX, GOAL_PLUGIN_PREFIX);

  /**
   * Web mapper 直接使用的 canonical tool types (EnvironmentBinding, EnvironmentName,
   * ToolResultJsonCodec).
   */
  private static final List<String> ALLOWED_HARNESS_TOOL_IMPORTS =
      List.of(
          HARNESS_TOOL_PREFIX + "EnvironmentBinding",
          HARNESS_TOOL_PREFIX + "EnvironmentName",
          HARNESS_TOOL_PREFIX + "codec." + "ToolResultJsonCodec");

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          PLATFORM_AI_REFERENCE_PREFIX + "environment.gateway." + "EnvironmentDaemonGateway",
          PLATFORM_AI_REFERENCE_PREFIX + "environment.registry." + "LiveEnvironment",
          PLATFORM_AI_REFERENCE_PREFIX + "environment.registry." + "LiveEnvironmentRegistry");
  private static final List<String> FORBIDDEN_POM_ARTIFACTS =
      List.of("kk-studio-harness-tool", "kk-studio-harness-daemon");
  private static final List<String> REQUIRED_POM_ARTIFACTS =
      List.of("kk-studio-harness-infra", "kk-studio-harness-plugin-goal");

  @Test
  void webMainSourcesUseCoreBoundariesAndAvoidDirectHarnessDependencies() throws IOException {
    Path main = locateWebMainJava();
    assertTrue(Files.isDirectory(main), "web main sources must exist: " + main);

    List<String> importViolations = scanViolations(main);
    assertTrue(
        importViolations.isEmpty(),
        () -> "web boundary violations:\n" + String.join("\n", importViolations));

    Path pom = locateWebPom(main);
    assertTrue(Files.isRegularFile(pom), "web/pom.xml must exist: " + pom);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    List<String> pomViolations = new ArrayList<>();
    for (String artifactId : FORBIDDEN_POM_ARTIFACTS) {
      if (pomText.contains("<artifactId>" + artifactId + "</artifactId>")) {
        pomViolations.add("direct dependency declared: " + artifactId);
      }
    }
    for (String artifactId : REQUIRED_POM_ARTIFACTS) {
      if (!pomText.contains("<artifactId>" + artifactId + "</artifactId>")) {
        pomViolations.add("composition root must declare direct dependency: " + artifactId);
      }
    }
    assertTrue(
        pomViolations.isEmpty(),
        () -> "web pom harness dependency violations:\n" + String.join("\n", pomViolations));
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
                      if (isForbiddenHarnessImport(imported)) {
                        violations.add(relative(main, path) + ": " + trimmed);
                      }
                      for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                        if (imported.startsWith(prefix)) {
                          violations.add(relative(main, path) + ": " + trimmed);
                        }
                      }
                    } else if (isForbiddenHarnessReference(trimmed)) {
                      violations.add(
                          relative(main, path) + ": forbidden Harness reference " + trimmed);
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    return violations;
  }

  /** 组合根只直接引用 Runtime、Infra、内建 Goal 插件和规范化的 Tool 类型。 */
  private static boolean isForbiddenHarnessImport(String imported) {
    if (ALLOWED_HARNESS_PACKAGE_PREFIXES.stream().anyMatch(imported::startsWith)) {
      return false;
    }
    if (!imported.startsWith("fun.fengwk.kkstudio.harness.")) {
      return false;
    }
    return ALLOWED_HARNESS_TOOL_IMPORTS.stream().noneMatch(imported::equals);
  }

  private static boolean isForbiddenHarnessReference(String line) {
    return line.contains("fun.fengwk.kkstudio.harness.")
        && ALLOWED_HARNESS_PACKAGE_PREFIXES.stream().noneMatch(line::contains);
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

  private static Path locateWebMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates = List.of(cwd.resolve("src/main/java"), cwd.resolve("web/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate web main sources from " + cwd);
  }

  private static Path locateWebPom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("web/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException("cannot locate web/pom.xml from " + mainJava + " or " + cwd);
  }
}
