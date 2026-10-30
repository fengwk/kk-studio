package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 守护 platform 的组合边界：web 是唯一组合根，platform 不能成为 Spring Boot 启动入口，也不加载 trusted JAR；模块依赖方向由仓库检查 {@code
 * scripts/dev/verify/repository/check.mjs} 统一校验。
 */
class PlatformArchitectureTest {

  /** trusted JAR 发现和 classloader 生命周期只属于 web 组合根。 */
  private static final List<String> FORBIDDEN_TRUSTED_CONTRIBUTOR_REFERENCES =
      List.of(
          "TrustedJarContributorLoader",
          "URLClassLoader",
          "ServiceLoader",
          "kk-studio.harness.contributors.directory",
          "KK_STUDIO_TRUSTED_CONTRIBUTOR_DIRECTORY");

  /** platform 不是组合根：不得成为 Spring Boot 启动入口，trusted JAR 发现与 classloader 生命周期只属于 web。 */
  @Test
  void platformIsNeitherBootMainNorContributorLoader() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(c -> c.toString().endsWith(".java")).toList();
      for (Path path : javaFiles) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("@SpringBootApplication")
              || trimmed.startsWith("SpringApplication.run(")) {
            violations.add(relative(main, path) + ": boot main " + trimmed);
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
    assertTrue(
        violations.isEmpty(),
        () -> "Platform boundary violations:\n" + String.join("\n", violations));
  }

  /**
   * Environment gateway 只实现 Environment Capability transport，不得回引 harness.tool（ToolExecutionGateway
   * 是跨 Environment/Tool 域结果的唯一适配点）。
   */
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
        source.contains("fun.fengwk.kkstudio.harness.tool."),
        "EnvironmentDaemonGateway must not depend on harness tool package");
    assertFalse(
        source.contains("Remote" + "Tool"),
        "EnvironmentDaemonGateway must use Environment Capability terminology");
  }

  /** MCP 客户端已改用官方 SDK；全仓 POM 不允许重新引入 LangChain4j。 */
  @Test
  void reactorDeclaresNoLangChainDependencies() throws IOException {
    Path root = locateReactorRoot();
    List<String> violations = new ArrayList<>();
    for (Path pom : reactorPoms(root)) {
      String text = Files.readString(pom, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      if (text.contains("langchain4j")) {
        violations.add(root.relativize(pom) + ": langchain4j dependency");
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "LangChain4j dependencies must not return:\n" + String.join("\n", violations));
  }

  /** 生产代码不再导入 LangChain4j；MCP 客户端统一使用官方 SDK。 */
  @Test
  void productionSourcesDoNotImportLangChain() throws IOException {
    Path root = locateReactorRoot();
    List<String> violations = new ArrayList<>();
    for (Path pom : reactorPoms(root)) {
      Path srcMainJava = pom.getParent().resolve("src/main/java");
      if (!Files.isDirectory(srcMainJava)) {
        continue;
      }
      try (Stream<Path> javaPaths = Files.walk(srcMainJava)) {
        for (Path javaFile : javaPaths.filter(p -> p.toString().endsWith(".java")).toList()) {
          for (String line : Files.readAllLines(javaFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import dev.langchain4j.")
                || trimmed.startsWith("import io.github.langchain4j.")) {
              violations.add(root.relativize(javaFile) + ": " + trimmed);
            }
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "LangChain4j imports found in production sources:\n" + String.join("\n", violations));
  }

  /** 只枚举当前 reactor 的 POM：跳过构建产物与本地工作区副本，避免在开发机上把其它 worktree 的旧依赖当成当前仓库事实。 */
  @Test
  void reactorPomsSkipBuildOutputsAndWorkspaceCopies(@TempDir Path tempDir) throws IOException {
    Path module = Files.createDirectories(tempDir.resolve("module"));
    Path modulePom = Files.writeString(module.resolve("pom.xml"), "<project/>");
    Path target = Files.createDirectories(tempDir.resolve("target"));
    Files.writeString(target.resolve("pom.xml"), "<project/>");
    Path workspace = Files.createDirectories(tempDir.resolve(".workspace/worktree"));
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");

    assertEquals(List.of(modulePom), reactorPoms(tempDir));
  }

  private static List<Path> reactorPoms(Path root) throws IOException {
    List<Path> poms = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(root) && isSkippedDirectory(directory)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if ("pom.xml".equals(file.getFileName().toString())) {
              poms.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return poms;
  }

  private static boolean isSkippedDirectory(Path directory) {
    String name = directory.getFileName().toString();
    return name.startsWith(".") || "target".equals(name) || "node_modules".equals(name);
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
        List.of(cwd.resolve("src/main/java"), cwd.resolve("platform/runtime/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate platform main sources from " + cwd);
  }

  private static Path locateReactorRoot() {
    Path start;
    try {
      var codeSource = PlatformArchitectureTest.class.getProtectionDomain().getCodeSource();
      if (codeSource != null && codeSource.getLocation() != null) {
        start = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
      } else {
        start = Path.of("").toAbsolutePath().normalize();
      }
    } catch (Exception ignored) {
      start = Path.of("").toAbsolutePath().normalize();
    }
    Path current = start;
    while (current != null) {
      Path pom = current.resolve("pom.xml");
      if (Files.isRegularFile(pom) && isRootReactorPom(pom)) {
        return current;
      }
      current = current.getParent();
    }
    current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path pom = current.resolve("pom.xml");
      if (Files.isRegularFile(pom) && isRootReactorPom(pom)) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("cannot locate reactor root from " + start);
  }

  private static boolean isRootReactorPom(Path pom) {
    try {
      String text = Files.readString(pom, StandardCharsets.UTF_8);
      return text.contains("<artifactId>kk-studio-parent</artifactId>")
          && text.contains("<modules>")
          && !text.contains("<parent>");
    } catch (IOException ignored) {
      return false;
    }
  }
}
