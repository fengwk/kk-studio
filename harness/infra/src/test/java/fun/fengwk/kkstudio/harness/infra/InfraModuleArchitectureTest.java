package fun.fengwk.kkstudio.harness.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

/** 守护 Infra 只承载 Runtime/Tool 的 Spring JDBC 与 PostgreSQL 适配。 */
class InfraModuleArchitectureTest {

  private static final String PACKAGE_PREFIX = "package fun.fengwk.kkstudio.harness.infra";
  private static final Set<String> PRODUCTION_DEPENDENCIES =
      Set.of(
          "fun.fengwk.kk-studio:kk-studio-harness-runtime",
          "fun.fengwk.kk-studio:kk-studio-harness-tool",
          "org.postgresql:postgresql",
          "org.springframework:spring-jdbc");
  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "com.fasterxml.jackson.",
          "java.",
          "javax.",
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.tool.",
          "org.postgresql.",
          "org.slf4j.",
          "org.springframework.dao.",
          "org.springframework.jdbc.",
          "org.springframework.transaction.");

  /** 所有生产源码都必须留在 adapter 包内，且禁止引入 Core/Web 技术。 */
  @Test
  void mainSourcesStayInsideInfraBoundary() throws IOException {
    Path main = locateMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(main, path, violations));
    }
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** Infra 的直接生产依赖只允许 Runtime、Tool、Spring JDBC 与 PostgreSQL。 */
  @Test
  void pomDeclaresOnlyInfraProductionDependencies() throws IOException {
    Path pom = locateMainJava().getParent().getParent().getParent().resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
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

  private static void inspect(Path main, Path path, List<String> violations) {
    try {
      String source = Files.readString(path, StandardCharsets.UTF_8);
      boolean packageDeclared =
          source
              .lines()
              .map(String::trim)
              .anyMatch(line -> line.startsWith(PACKAGE_PREFIX) && line.endsWith(";"));
      if (!packageDeclared) {
        violations.add(main.relativize(path) + ": source is outside infra package");
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

  private static Path locateMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/infra/src/main/java"))) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate infra main sources from " + cwd);
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
