package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Project 模块的架构守卫：模块只承载 Project 领域与自身持久化，绝不反向依赖宿主。
 *
 * <p>Project 允许直接依赖 harness-runtime（会话/线程协议）、Spring、MyBatis、Jackson 与 convention4j；跨宿主能力必须经 {@code
 * fun.fengwk.kkstudio.project.port} 暴露并由宿主实现。本测试锁定这条边界，防止后续维护不小心把 platform/web 的类型或依赖重新引入模块，形成循环依赖。
 */
class ProjectArchitectureTest {

  private static final String OWN_PACKAGE = "fun.fengwk.kkstudio.project";
  private static final String OWN_PACKAGE_PREFIX = OWN_PACKAGE + ".";

  /** 宿主模块：出现即代表方向反转，必须失败。 */
  private static final Set<String> HOST_PACKAGE_PREFIXES =
      Set.of(
          "fun.fengwk.kkstudio.platform.",
          "fun.fengwk.kkstudio.web.",
          "fun.fengwk.kkstudio.canvas.");

  /** 允许的非 JDK 导入前缀：只列模块真实使用、且不引入宿主语义的能力。 */
  private static final Set<String> ALLOWED_EXTERNAL_IMPORT_PREFIXES =
      Set.of(
          "com.fasterxml.jackson.",
          "fun.fengwk.convention4j.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "lombok.",
          "org.apache.ibatis.",
          "org.mybatis.",
          "org.springframework.");

  /** 允许声明的生产依赖：与上面的导入前缀一一对应，新增依赖必须同步在这里显式登记。 */
  private static final List<String> EXPECTED_PRODUCTION_DEPENDENCIES =
      List.of(
          "fun.fengwk.kk-studio:kk-studio-harness-runtime",
          "fun.fengwk.convention4j:convention4j-spring-boot-starter",
          "org.mybatis.spring.boot:mybatis-spring-boot-starter",
          "com.fasterxml.jackson.core:jackson-databind");

  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  /** 主源码不得导入或书写宿主模块类型：跨宿主能力只能走 project.port 接口。 */
  @Test
  void mainSourcesNeverReferenceHostModules() throws IOException {
    List<String> violations = new ArrayList<>();
    forEachMainSourceLine(
        (path, line) -> {
          for (String prefix : HOST_PACKAGE_PREFIXES) {
            if (line.contains(prefix)) {
              violations.add(relative(path) + ": host reference " + line.trim());
            }
          }
        });
    assertTrue(
        violations.isEmpty(),
        () -> "project must not depend on host modules:\n" + String.join("\n", violations));
  }

  /** 主源码只用 JDK、自身包、harness-runtime 协议与既定的基础框架能力，其余导入一律视为失控耦合。 */
  @Test
  void mainSourcesOnlyUseAllowedImports() throws IOException {
    List<String> violations = new ArrayList<>();
    forEachMainSourceLine(
        (path, line) -> {
          String trimmed = line.trim();
          if (trimmed.startsWith("package ")) {
            String declared = trimmed.replace(";", "").substring("package ".length()).trim();
            if (!declared.equals(OWN_PACKAGE) && !declared.startsWith(OWN_PACKAGE_PREFIX)) {
              violations.add(relative(path) + ": disallowed package " + declared);
            }
          } else if (trimmed.startsWith("import ")) {
            String imported = normalizeImport(trimmed);
            if (!isAllowedImport(imported)) {
              violations.add(relative(path) + ": disallowed import " + imported);
            }
          }
        });
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** 生产依赖集合必须与允许导入的能力一致，且永不声明宿主模块。 */
  @Test
  void pomDeclaresOnlyHostNeutralProductionDependencies() throws IOException {
    String pom = Files.readString(moduleRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        pom.contains("<artifactId>kk-studio-project</artifactId>"),
        "project artifactId must stay stable");
    assertFalse(
        pom.contains("kk-studio-platform"),
        "project must not declare a dependency on kk-studio-platform");

    List<String> production = new ArrayList<>();
    Matcher matcher = DEPENDENCY_PATTERN.matcher(pom);
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!"test".equals(optionalTag(dependency, "scope"))) {
        production.add(
            requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId"));
      }
    }
    assertEquals(EXPECTED_PRODUCTION_DEPENDENCIES, production);
  }

  private static void forEachMainSourceLine(LineConsumer consumer) throws IOException {
    Path main = moduleRoot().resolve("src/main/java");
    assertTrue(Files.isDirectory(main), "project main sources must exist: " + main);
    try (Stream<Path> stream = Files.walk(main)) {
      for (Path path : stream.filter(source -> source.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          consumer.accept(path, line);
        }
      }
    }
  }

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("jdk.")
        || imported.startsWith(OWN_PACKAGE_PREFIX)
        || ALLOWED_EXTERNAL_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    return imported.startsWith("static ")
        ? imported.substring("static ".length()).trim()
        : imported;
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

  private static String relative(Path path) {
    return moduleRoot().resolve("src/main/java").relativize(path).toString();
  }

  private static Path moduleRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.resolve("project"))) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate project module from " + cwd);
  }

  /** 逐行消费主源码的回调。 */
  @FunctionalInterface
  private interface LineConsumer {

    void accept(Path path, String line);
  }
}
