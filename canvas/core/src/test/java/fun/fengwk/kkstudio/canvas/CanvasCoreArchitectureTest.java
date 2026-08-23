package fun.fengwk.kkstudio.canvas;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Canvas Core 的轻量级架构守卫，锁定纯领域源码和零生产依赖边界。 */
class CanvasCoreArchitectureTest {

  private static final String OWN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.canvas.";
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
          "org.springframework.");
  private static final Pattern DEPENDENCY_PATTERN =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

  @Test
  void mainSourcesUseOnlyJdkAndCanvasPackages() throws IOException {
    // 扫描全部主源码，避免领域模块通过新增 package 或全限定名绕过依赖边界。
    Path main = locateModuleRoot().resolve("src/main/java");
    assertTrue(Files.isDirectory(main), "canvas core main sources must exist: " + main);
    assertFalse(
        Files.exists(main.resolve("fun/fengwk/kkstudio/studio")),
        "legacy studio package tree must not exist");

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  @Test
  void pomHasNoProductionDependencies() throws IOException {
    // 允许测试依赖，但任何 compile/runtime/provided/system 依赖都会破坏纯领域边界。
    Path pom = locateModuleRoot().resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    assertTrue(
        text.contains("<artifactId>kk-studio-canvas-core</artifactId>"),
        "canvas core artifactId must stay stable");

    List<String> productionDependencies = new ArrayList<>();
    Matcher matcher = DEPENDENCY_PATTERN.matcher(text);
    while (matcher.find()) {
      String dependency = matcher.group(1);
      if (!"test".equals(optionalTag(dependency, "scope"))) {
        productionDependencies.add(
            requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId"));
      }
    }
    assertEquals(List.of(), productionDependencies, "canvas core production dependencies");
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
    List<Path> candidates = List.of(cwd, cwd.resolve("canvas/core"));
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate.resolve("src/main/java"))
          && Files.isRegularFile(candidate.resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate canvas core module from " + cwd);
  }
}
