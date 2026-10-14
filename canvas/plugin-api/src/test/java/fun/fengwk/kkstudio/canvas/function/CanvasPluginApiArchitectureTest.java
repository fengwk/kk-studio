package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Canvas Plugin API 的轻量级架构守卫，锁定 SPI 源码只依赖 JDK 与 canvas-core，并保持 Catalog 全仓单一事实源。
 *
 * <p>本模块不引入 Spring、MyBatis、Harness、Platform、Share、Web 或 Jackson
 * databind：adapter、宿主装配与持久化实现必须留在消费方模块。
 */
class CanvasPluginApiArchitectureTest {

  private static final String OWN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.canvas.";
  private static final List<String> ALLOWED_PRODUCTION_DEPENDENCIES =
      List.of("fun.fengwk.kk-studio:kk-studio-canvas-core");
  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "java.sql.",
          "javax.sql.",
          "jakarta.persistence.",
          "fun.fengwk.kkstudio.harness.",
          "fun.fengwk.kkstudio.platform.",
          "fun.fengwk.kkstudio.share.",
          "fun.fengwk.kkstudio.web.",
          "org.apache.ibatis.",
          "org.mybatis.",
          "org.springframework.",
          "tools.jackson.databind.");
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
  private static final Set<String> GENERATED_DIRS =
      Set.of(".git", ".workspace", "target", "node_modules", "dist", "coverage", "reports");

  @Test
  void mainSourcesUseOnlyJdkAndCanvasPackages() throws IOException {
    // SPI 主源码只能使用 JDK 与 canvas 包，任何第三方 import 都会破坏纯 API 边界。
    Path main = locateModuleRoot().resolve("src/main/java");
    assertTrue(Files.isDirectory(main), "canvas plugin-api main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  @Test
  void productionFunctionSpiPackageMustLiveInPluginApi() {
    // SPI 生产源码必须落在 canvas-plugin-api 的 function 包内。
    Path functionPackage =
        locateModuleRoot().resolve("src/main/java/fun/fengwk/kkstudio/canvas/function");
    assertTrue(
        Files.isDirectory(functionPackage),
        "canvas plugin-api must own the canvas.function SPI package: " + functionPackage);
  }

  @Test
  void pomDeclaresOnlyCanvasCoreAsProductionDependency() throws IOException {
    // 允许测试依赖；生产依赖只允许 canvas-core（其传递依赖由 Core 自身管理）。
    Path pom = locateModuleRoot().resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    assertTrue(
        text.contains("<artifactId>kk-studio-canvas-plugin-api</artifactId>"),
        "canvas plugin-api artifactId must stay stable");

    List<String> productionDependencies = new ArrayList<>();
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!"test".equals(optionalTag(dependency, "scope"))) {
        productionDependencies.add(
            requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId"));
      }
    }
    assertEquals(
        ALLOWED_PRODUCTION_DEPENDENCIES,
        productionDependencies,
        "canvas plugin-api production dependencies");
  }

  @Test
  void canvasFunctionCatalogHasOneFinalFactSourceAndNoRegistryImplementation() throws IOException {
    Path repositoryRoot = locateModuleRoot().resolve("../..").normalize();
    List<Path> catalogSources = mainJavaSources(repositoryRoot, "CanvasFunctionCatalog.java");
    assertEquals(
        List.of(
            repositoryRoot.resolve(
                "canvas/plugin-api/src/main/java/fun/fengwk/kkstudio/canvas/function/"
                    + "CanvasFunctionCatalog.java")),
        catalogSources);
    assertTrue(
        mainJavaSources(repositoryRoot, "CanvasFunction" + "ModelRegistry.java").isEmpty(),
        "Canvas Function model registry source must not exist");
    assertTrue(
        implementationSources(repositoryRoot).isEmpty(),
        "Canvas Function Catalog must not have a second implementation");
  }

  private static List<String> scanViolations(Path main) throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("package ") && !isOwnPackage(trimmed)) {
                      violations.add(relative(main, path) + ": disallowed package " + trimmed);
                    }
                    if (trimmed.startsWith("import ")) {
                      String imported = normalizeImport(trimmed);
                      if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                        violations.add(relative(main, path) + ": forbidden import " + trimmed);
                      } else if (!isAllowedImport(imported)) {
                        violations.add(relative(main, path) + ": disallowed import " + trimmed);
                      }
                      continue;
                    }
                    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                      if (trimmed.contains(prefix)) {
                        violations.add(relative(main, path) + ": forbidden reference " + trimmed);
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

  private static List<Path> mainJavaSources(Path repositoryRoot, String fileName)
      throws IOException {
    try (Stream<Path> stream = Files.walk(repositoryRoot)) {
      return stream
          .filter(path -> isRepositorySource(repositoryRoot, path))
          .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
          .filter(path -> path.getFileName().toString().equals(fileName))
          .toList();
    }
  }

  private static List<Path> implementationSources(Path repositoryRoot) throws IOException {
    try (Stream<Path> stream = Files.walk(repositoryRoot)) {
      return stream
          .filter(path -> isRepositorySource(repositoryRoot, path))
          .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
          .filter(path -> path.toString().endsWith(".java"))
          .filter(
              path -> {
                try {
                  return Files.readString(path, StandardCharsets.UTF_8)
                      .contains("implements CanvasFunctionCatalog");
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              })
          .toList();
    }
  }

  /** 路径任一段命中生成/依赖目录名时不算仓库源文件。 */
  private static boolean isRepositorySource(Path repositoryRoot, Path path) {
    for (Path segment : repositoryRoot.relativize(path)) {
      if (GENERATED_DIRS.contains(segment.toString())) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("jdk.")
        || imported.startsWith(OWN_PACKAGE_PREFIX);
  }

  private static boolean isOwnPackage(String packageLine) {
    return packageLine.equals("package fun.fengwk.kkstudio.canvas;")
        || packageLine.startsWith("package fun.fengwk.kkstudio.canvas.");
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
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

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
  }

  private static Path locateModuleRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates = List.of(cwd, cwd.resolve("canvas/plugin-api"));
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate.resolve("src/main/java"))
          && Files.isRegularFile(candidate.resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate canvas plugin-api module from " + cwd);
  }
}
