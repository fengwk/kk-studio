package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 守护 platform 与 framework-free Harness runtime API 的组合/应用边界：platform 提供应用能力与 Port 适配，不依赖
 * infra/web/daemon 下游模块；web 是唯一组合根，platform 不能成为 Spring Boot 启动入口。
 */
class PlatformArchitectureTest {

  /** platform main 源码禁止直接 import 的下游模块包前缀。 */
  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.canvas.infra.",
          "fun.fengwk.kkstudio.web.",
          "fun.fengwk.kkstudio.harness.daemon.");

  /** platform/pom.xml 禁止声明的下游模块 artifactId。 */
  private static final List<String> FORBIDDEN_POM_ARTIFACTS =
      List.of("kk-studio-harness-infra", "kk-studio-web", "kk-studio-harness-daemon");

  /** trusted JAR 发现和 classloader 生命周期只属于 web 组合根。 */
  private static final List<String> FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES =
      List.of(
          "TrustedJarContributorLoader",
          "URLClassLoader",
          "ServiceLoader",
          "kk-studio.harness.contributors.directory",
          "KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY");

  /** platform 不是组合根：main 源码禁止 import framework 基础设施与 web/daemon；pom 不得声明对应的下游模块 artifactId。 */
  @Test
  void platformNeverDependsOnInfraOrWebOrDaemon() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    List<String> bootMainViolations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(c -> c.toString().endsWith(".java")).toList();
      for (Path path : javaFiles) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("@SpringBootApplication")
              || trimmed.startsWith("SpringApplication.run(")) {
            bootMainViolations.add(relative(main, path) + ": " + trimmed);
          }
          if (!trimmed.startsWith("import ")) {
            for (String forbidden : FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES) {
              if (line.contains(forbidden)) {
                violations.add(
                    relative(main, path) + ": forbidden trusted contributor reference " + line);
              }
            }
            continue;
          }
          String imported = normalizeImport(trimmed);
          for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
            if (imported.startsWith(prefix)) {
              violations.add(relative(main, path) + ": " + trimmed);
            }
          }
          for (String forbidden : FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES) {
            if (line.contains(forbidden)) {
              violations.add(
                  relative(main, path) + ": forbidden trusted contributor reference " + line);
            }
          }
        }
      }
    }

    Path pom = locatePlatformPom(main);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    for (String artifactId : FORBIDDEN_POM_ARTIFACTS) {
      assertFalse(
          pomText.contains("<artifactId>" + artifactId + "</artifactId>"),
          "platform/pom.xml must not declare " + artifactId);
    }
    assertCanvasInfraIsTestScoped(pomText);

    assertTrue(
        violations.isEmpty(),
        () -> "Platform boundary violations:\n" + String.join("\n", violations));
    assertTrue(
        bootMainViolations.isEmpty(),
        () ->
            "Platform must not become a Spring Boot main:\n"
                + String.join("\n", bootMainViolations));
  }

  /** Environment gateway 只实现 Environment Capability transport，不依赖已删除的 remote package。 */
  @Test
  void environmentGatewayUsesCapabilityTransportOnly() throws IOException {
    Path gateway =
        locatePlatformMainJava()
            .resolve(
                "fun/fengwk/kkstudio/platform/environment/gateway/"
                    + "EnvironmentDaemonGateway.java");
    assertTrue(Files.isRegularFile(gateway), "EnvironmentDaemonGateway must exist");
    String source = Files.readString(gateway, StandardCharsets.UTF_8);
    assertFalse(
        source.contains("fun.fengwk.kkstudio.harness.tool." + "remote"),
        "EnvironmentDaemonGateway must not depend on the remote package");
    assertFalse(
        source.contains("Remote" + "Tool"),
        "EnvironmentDaemonGateway must use Environment Capability terminology");
  }

  /**
   * path pattern 的 gitignore 语义只在 harness-runtime 的 {@code PermissionPathPattern} 内部持有；platform
   * 永远不直接 import JGit。
   */
  @Test
  void platformNeverImportsJGitDirectly() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(c -> c.toString().endsWith(".java")).toList();
      for (Path path : javaFiles) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("import ")
              && normalizeImport(trimmed).startsWith("org.eclipse.jgit")) {
            violations.add(relative(main, path) + ": " + trimmed);
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "Platform JGit imports must not exist:\n" + String.join("\n", violations));
  }

  private static void assertCanvasInfraIsTestScoped(String pomText) {
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pomText);
    int declarations = 0;
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!dependency.contains("<artifactId>kk-studio-canvas-infra</artifactId>")) {
        continue;
      }
      declarations++;
      assertTrue(
          dependency.contains("<scope>test</scope>"),
          "platform may depend on kk-studio-canvas-infra only in test scope");
    }
    assertTrue(declarations == 1, "platform must declare one test-scoped canvas infra dependency");
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
  }

  /**
   * 当 Maven 在模块根或 reactor 根运行时解析 {@code src/main/java}，不会解析到 {@code target/} 下；优先匹配模块内的 main 源码，再回退
   * reactor 根的相对路径。
   */
  private static Path locatePlatformMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("platform/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate platform main sources from " + cwd);
  }

  private static Path locatePlatformPom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("platform/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException(
        "cannot locate platform/pom.xml from " + mainJava + " or " + cwd);
  }
}
