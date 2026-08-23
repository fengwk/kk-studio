package fun.fengwk.kkstudio.canvas.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 守护 Canvas Infra 只承载 Canvas Core 的 PostgreSQL/MyBatis、codec 与 Function Runtime。 */
class CanvasInfraArchitectureTest {

  private static final String PACKAGE_PREFIX = "package fun.fengwk.kkstudio.canvas.infra";
  private static final Set<String> PRODUCTION_DEPENDENCIES =
      Set.of(
          "com.fasterxml.jackson.core:jackson-databind",
          "fun.fengwk.convention4j:convention4j-spring-boot-starter",
          "fun.fengwk.kk-studio:kk-studio-canvas-core",
          "org.mybatis.spring.boot:mybatis-spring-boot-starter");
  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "com.fasterxml.jackson.",
          "fun.fengwk.convention4j.",
          "fun.fengwk.kkstudio.canvas.",
          "java.",
          "javax.",
          "lombok.",
          "org.apache.ibatis.",
          "org.springframework.");

  /** 生产源码必须留在 infra 包内，且不能反向依赖 Platform/Harness/Web。 */
  @Test
  void mainSourcesStayInsideCanvasInfraBoundary() throws IOException {
    Path main = locateModuleRoot().resolve("src/main/java");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(main, path, violations));
    }
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** Infra 的直接生产依赖只允许 Core、Jackson、Spring 与 MyBatis 装配能力。 */
  @Test
  void pomDeclaresOnlyCanvasInfraProductionDependencies() throws IOException {
    String text = Files.readString(locateModuleRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
    Set<String> dependencies = new HashSet<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if ("test".equals(optionalTag(dependency, "scope"))) {
        continue;
      }
      dependencies.add(
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId"));
    }
    assertEquals(PRODUCTION_DEPENDENCIES, dependencies);
  }

  /** Spring Boot 自动配置入口必须唯一指向 Canvas Infra 装配类。 */
  @Test
  void autoConfigurationImportsCanvasInfraAssembly() throws IOException {
    Path imports =
        locateModuleRoot()
            .resolve(
                "src/main/resources/META-INF/spring/"
                    + String.join(
                        ".",
                        "org",
                        "springframework",
                        "boot",
                        "autoconfigure",
                        "AutoConfiguration",
                        "imports"));
    assertEquals(
        String.join(
                ".", "fun", "fengwk", "kkstudio", "canvas", "infra", "CanvasInfraAutoConfiguration")
            + "\n",
        Files.readString(imports, StandardCharsets.UTF_8));
  }

  /** Function Runtime 的部署属性必须由唯一 Infra 自动配置入口显式启用。 */
  @Test
  void autoConfigurationEnablesFunctionRuntimeProperties() {
    EnableConfigurationProperties annotation =
        CanvasInfraAutoConfiguration.class.getAnnotation(EnableConfigurationProperties.class);
    assertEquals(List.of(CanvasFunctionRuntimeProperties.class), List.of(annotation.value()));
  }

  private static void inspect(Path main, Path path, List<String> violations) {
    try {
      String source = Files.readString(path, StandardCharsets.UTF_8);
      boolean packageDeclared =
          source
              .lines()
              .map(String::trim)
              .anyMatch(line -> line.startsWith(PACKAGE_PREFIX) && line.endsWith(";"));
      if (!packageDeclared) {
        violations.add(main.relativize(path) + ": source is outside canvas infra package");
      }
      for (String line : source.split("\\R")) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        String imported =
            trimmed.substring("import ".length()).replace(";", "").replace("static ", "").trim();
        if (ALLOWED_IMPORT_PREFIXES.stream().noneMatch(imported::startsWith)) {
          violations.add(main.relativize(path) + ": disallowed import " + trimmed);
        }
      }
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Path locateModuleRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.resolve("canvas/infra"))) {
      if (Files.isDirectory(candidate.resolve("src/main/java"))
          && Files.isRegularFile(candidate.resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate canvas infra module from " + cwd);
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
