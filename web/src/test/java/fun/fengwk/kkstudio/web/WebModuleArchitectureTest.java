package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * web 模块的轻量架构守护。
 *
 * <p>生产源码不得引用 Harness Tool execution/Daemon 包或选定的 Core 基础设施实现；DTO mapping 只放行列出的 canonical Tool
 * 类型；Driver 类型只允许精确的组合根使用；web.controller 包不得引用 Harness runtime processor。web 模块是 Harness 组合根：它按设计将
 * Runtime、Builtin Driver 与 infra 适配器声明为直接依赖。
 */
class WebModuleArchitectureTest {

  private static final String HARNESS_RUNTIME_PREFIX = "fun.fengwk.kkstudio.harness.runtime.";
  private static final String HARNESS_RUNTIME_PROCESSOR_PREFIX =
      "fun.fengwk.kkstudio.harness.runtime.processor.";
  private static final String HARNESS_INFRA_PREFIX = "fun.fengwk.kkstudio.harness.infra.";
  private static final String HARNESS_CONTRIBUTOR_API_PREFIX =
      "fun.fengwk.kkstudio.harness.contributor.api.";
  private static final String HARNESS_ENVIRONMENT_PREFIX =
      "fun.fengwk.kkstudio.harness.environment.";
  private static final String HARNESS_TOOL_PREFIX = "fun.fengwk.kkstudio.harness.tool.";
  private static final String HARNESS_MODEL_PREFIX = "fun.fengwk.kkstudio.harness.model.";
  private static final String PLATFORM_ENVIRONMENT_PREFIX =
      "fun.fengwk.kkstudio.platform.environment.";
  private static final List<String> ALLOWED_HARNESS_PACKAGE_PREFIXES =
      List.of(
          HARNESS_RUNTIME_PREFIX,
          HARNESS_INFRA_PREFIX,
          HARNESS_CONTRIBUTOR_API_PREFIX,
          HARNESS_ENVIRONMENT_PREFIX);

  /** Web DTO 适配直接使用的公共值类型，不包含 Model/Driver 的私有执行实现。 */
  private static final List<String> ALLOWED_HARNESS_VALUE_IMPORTS =
      List.of(
          HARNESS_TOOL_PREFIX + "codec.ToolResultJsonCodec",
          HARNESS_MODEL_PREFIX + "ImageInputTier");

  private static final String WEB_SOURCE_ROOT = "fun/fengwk/kkstudio/web/";
  private static final String BUILTIN_PREFIX = "fun.fengwk.kkstudio.harness.builtin.";

  /** 精确按文件和类型放行真实装配；不为 controller、普通 mapper 或任意 Driver 私有包开通配。 */
  private static final Map<String, List<String>> ALLOWED_DRIVER_IMPORTS =
      Map.of(
          WEB_SOURCE_ROOT + "runtime/HarnessRuntimeConfiguration.java",
          List.of(
              BUILTIN_PREFIX + "driver.BuiltinAgentDriver",
              BUILTIN_PREFIX + "port.TurnResolver",
              BUILTIN_PREFIX + "processor.ModelProcessor",
              BUILTIN_PREFIX + "query.BuiltinRequestQuery"),
          WEB_SOURCE_ROOT + "runtime/HarnessProcessorConfiguration.java",
          List.of(
              BUILTIN_PREFIX + "port.ModelGateway",
              BUILTIN_PREFIX + "processor.ModelProcessor",
              BUILTIN_PREFIX + "processor.ModelProcessorConfig",
              BUILTIN_PREFIX + "processor.ModelStreamConfig"),
          WEB_SOURCE_ROOT + "runtime/HarnessRealtimeConfiguration.java",
          List.of(BUILTIN_PREFIX + "processor.ModelProcessor"));

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          PLATFORM_ENVIRONMENT_PREFIX + "gateway." + "EnvironmentDaemonGateway",
          PLATFORM_ENVIRONMENT_PREFIX + "registry." + "EnvironmentConnection",
          PLATFORM_ENVIRONMENT_PREFIX + "registry." + "EnvironmentRegistry");

  /**
   * web/pom.xml 禁止直接声明的依赖项：{@code kk-studio-common} 必须由 {@code kk-studio-platform-runtime} 在
   * compile scope 传递引入；严禁在 web 声明任何 scope（特别是 test scope）的直接依赖。若声明更近的 test 依赖，Maven 最近依赖优先原则会遮蔽
   * platform 的 compile-scope 传递依赖，导致 Spring Boot repackage 将其视为 test 依赖而从 Fat JAR 的 BOOT-INF/lib
   * 中剔除。
   */
  private static final List<String> FORBIDDEN_POM_ARTIFACTS = List.of("kk-studio-common");

  private static final List<String> REQUIRED_POM_ARTIFACTS =
      List.of(
          "kk-studio-platform-plugin-api",
          "kk-studio-platform-runtime",
          "kk-studio-canvas-infra",
          "kk-studio-harness-infra",
          "kk-studio-harness-environment",
          "kk-studio-harness-runtime",
          "kk-studio-harness-agent-builtin",
          "kk-studio-harness-model");

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
        pomViolations.add(
            "direct dependency declared (forbidden in any scope): "
                + artifactId
                + " (nearer dependency shadows platform compile-scope dependency and omits jar from Fat JAR)");
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

  /** 迁移后的组合根白名单不能放开普通 controller/mapper。 */
  @Test
  void driverCompositionPermissionsDoNotEscapeTheirDeclaredSourceFiles() {
    String composition = WEB_SOURCE_ROOT + "runtime/HarnessRuntimeConfiguration.java";
    String processor = WEB_SOURCE_ROOT + "runtime/HarnessProcessorConfiguration.java";
    String realtime = WEB_SOURCE_ROOT + "runtime/HarnessRealtimeConfiguration.java";
    String controller = WEB_SOURCE_ROOT + "controller/StudioHarnessThreadController.java";
    assertFalse(
        isForbiddenHarnessImport(composition, BUILTIN_PREFIX + "driver.BuiltinAgentDriver"));
    assertFalse(
        isForbiddenHarnessImport(processor, BUILTIN_PREFIX + "processor.ModelProcessorConfig"));
    assertFalse(isForbiddenHarnessImport(realtime, BUILTIN_PREFIX + "processor.ModelProcessor"));
    assertTrue(isForbiddenHarnessImport(controller, BUILTIN_PREFIX + "driver.BuiltinAgentDriver"));
    assertTrue(
        isForbiddenHarnessImport(composition, BUILTIN_PREFIX + "processor.ModelProcessorConfig"));
    assertTrue(isForbiddenHarnessImport(processor, BUILTIN_PREFIX + "driver.BuiltinAgentDriver"));
    assertTrue(
        isForbiddenHarnessImport(realtime, BUILTIN_PREFIX + "processor.ModelProcessorConfig"));
    assertTrue(
        isForbiddenHarnessImport(composition, HARNESS_MODEL_PREFIX + "provider.ProviderResponse"));
  }

  private static final List<String> FORBIDDEN_MAIN_SOURCE_TOKENS =
      List.of(
          "TrustedJarContributorLoader",
          "URLClassLoader",
          "ServiceLoader",
          "kk-studio.harness.contributors.directory",
          "KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY");

  private static final String FORBIDDEN_PLUGIN_IMPORT_PREFIX = "fun.fengwk.kkstudio.plugin.";

  /**
   * Plugin 部署配置的 wire 名：运维只承诺这些环境变量，因此 application.yml 必须显式把它们映射到 canonical 属性。
   *
   * <p>刷新节奏刻意不使用 relaxed binding：Spring 会把环境变量里的下划线当作层级分隔，{@code
   * KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY} 不会直接命中 {@code refresh.poll-delay}，只有 yml 里的显式占位符能让它生效。
   */
  private static final List<String> REQUIRED_PLUGIN_CONFIGURATION_LINES =
      List.of(
          "poll-delay: ${KK_STUDIO_PLUGINS_REFRESH_POLL_DELAY:1h}",
          "lease-duration: ${KK_STUDIO_PLUGINS_REFRESH_LEASE_DURATION:2m}");

  @Test
  void pluginDeploymentConfigurationKeepsDocumentedEnvironmentNames() throws IOException {
    Path applicationYml = locateWebApplicationYml();
    String yml = Files.readString(applicationYml, StandardCharsets.UTF_8);
    List<String> violations = new ArrayList<>();
    for (String line : REQUIRED_PLUGIN_CONFIGURATION_LINES) {
      if (!yml.contains(line)) {
        violations.add("application.yml must declare exactly: " + line);
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "plugin deployment configuration violations:\n" + String.join("\n", violations));
  }

  @Test
  void webMainSourcesContainNoTrustedJarRemnantsOrPluginImplementationImports() throws IOException {
    Path main = locateWebMainJava();
    assertTrue(Files.isDirectory(main), "web main sources must exist: " + main);
    List<String> tokenViolations = new ArrayList<>();
    List<String> pluginImportViolations = new ArrayList<>();

    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !isGeneratedOrTarget(path))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  for (String token : FORBIDDEN_MAIN_SOURCE_TOKENS) {
                    if (source.contains(token)) {
                      tokenViolations.add(
                          relative(main, path) + " contains forbidden remnant: " + token);
                    }
                  }
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                      String imported = normalizeImport(trimmed);
                      if (imported.startsWith(FORBIDDEN_PLUGIN_IMPORT_PREFIX)) {
                        pluginImportViolations.add(relative(main, path) + ": " + trimmed);
                      }
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }

    assertTrue(
        tokenViolations.isEmpty(),
        () ->
            "web main sources must not contain trusted JAR remnants:\n"
                + String.join("\n", tokenViolations));
    assertTrue(
        pluginImportViolations.isEmpty(),
        () ->
            "web main sources must not import plugin implementations:\n"
                + String.join("\n", pluginImportViolations));
  }

  @Test
  void webControllersDoNotDependOnHarnessRuntimeProcessors() throws IOException {
    Path main = locateWebMainJava();
    Path controllerDir = main.resolve("fun/fengwk/kkstudio/web/controller");
    assertTrue(
        Files.isDirectory(controllerDir), "web controller directory must exist: " + controllerDir);
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(controllerDir)) {
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
                      if (imported.startsWith(HARNESS_RUNTIME_PROCESSOR_PREFIX)) {
                        violations.add(relative(main, path) + ": " + trimmed);
                      }
                    } else if (trimmed.contains(HARNESS_RUNTIME_PROCESSOR_PREFIX)) {
                      violations.add(
                          relative(main, path) + ": forbidden processor reference " + trimmed);
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "web controller boundary violations (controllers must not depend on harness runtime processor):\n"
                + String.join("\n", violations));
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
                      if (isForbiddenHarnessImport(relative(main, path), imported)) {
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

  /** 公共类型沿用公开边界；Driver 实现只能出现在声明的组合根中。 */
  private static boolean isForbiddenHarnessImport(String source, String imported) {
    if (ALLOWED_HARNESS_PACKAGE_PREFIXES.stream().anyMatch(imported::startsWith)) {
      return false;
    }
    if (!imported.startsWith("fun.fengwk.kkstudio.harness.")) {
      return false;
    }
    return !ALLOWED_HARNESS_VALUE_IMPORTS.contains(imported)
        && !ALLOWED_DRIVER_IMPORTS.getOrDefault(source, List.of()).contains(imported);
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
      return main.relativize(path).toString().replace('\\', '/');
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

  private static Path locateWebApplicationYml() {
    Path main = locateWebMainJava();
    Path resources = main.getParent().resolve("resources/application.yml").normalize();
    if (!Files.isRegularFile(resources)) {
      throw new IllegalStateException("cannot locate web application.yml from " + main);
    }
    return resources;
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
