package fun.fengwk.kkstudio.project.domain;

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

/** Project 模块的轻量架构守卫：领域源码只用 JDK 与 workflow JSON 编解码依赖。 */
class ProjectDomainArchitectureTest {

  private static final String OWN_PACKAGE = "fun.fengwk.kkstudio.project";
  private static final String OWN_PACKAGE_PREFIX = OWN_PACKAGE + ".";
  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "java.sql.",
          "javax.sql.",
          "jakarta.persistence.",
          "fun.fengwk.kkstudio.canvas.",
          "fun.fengwk.kkstudio.harness.",
          "fun.fengwk.kkstudio.platform.",
          "fun.fengwk.kkstudio.share.",
          "fun.fengwk.kkstudio.web.",
          "org.apache.ibatis.",
          "org.mybatis.",
          "org.springframework.");
  private static final Set<String> ALLOWED_EXTERNAL_IMPORT_PREFIXES =
      Set.of("com.fasterxml.jackson.");
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  /** 扫描全部主源码：基础设施、Spring/MyBatis 与其他产品模块一律禁止，唯一外部依赖是 Jackson 编解码。 */
  @Test
  void mainSourcesStayInsideJdkOwnPackagesAndJsonCodec() throws IOException {
    Path main = moduleRoot().resolve("src/main/java");
    assertTrue(Files.isDirectory(main), "project main sources must exist: " + main);

    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      for (Path path : stream.filter(source -> source.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          collectViolations(main, path, line, violations);
        }
      }
    }
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** 生产依赖只有 workflow JSON 编解码；Harness、Schema、PostgreSQL 等能力留给外层服务接入。 */
  @Test
  void pomDeclaresOnlyJsonCodecAsProductionDependency() throws IOException {
    String pom = Files.readString(moduleRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
    assertTrue(
        pom.contains("<artifactId>kk-studio-project</artifactId>"),
        "project artifactId must stay stable");

    List<String> production = new ArrayList<>();
    Matcher matcher = DEPENDENCY_PATTERN.matcher(pom);
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!"test".equals(optionalTag(dependency, "scope"))) {
        production.add(
            requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId"));
      }
    }
    assertEquals(List.of("com.fasterxml.jackson.core:jackson-databind"), production);
  }

  private static void collectViolations(
      Path main, Path path, String line, List<String> violations) {
    String trimmed = line.trim();
    if (trimmed.startsWith("package ")) {
      String declared = trimmed.replace(";", "").substring("package ".length()).trim();
      if (!declared.equals(OWN_PACKAGE) && !declared.startsWith(OWN_PACKAGE_PREFIX)) {
        violations.add(relative(main, path) + ": disallowed package " + declared);
      }
      return;
    }
    if (trimmed.startsWith("import ")) {
      String imported = normalizeImport(trimmed);
      if (FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
        violations.add(relative(main, path) + ": forbidden import " + imported);
      } else if (!isAllowedImport(imported)) {
        violations.add(relative(main, path) + ": disallowed import " + imported);
      }
      return;
    }
    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
      if (trimmed.contains(prefix)) {
        violations.add(relative(main, path) + ": forbidden reference " + trimmed);
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

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
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
}
