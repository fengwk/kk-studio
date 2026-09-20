package fun.fengwk.kkstudio.plugin.minimaxmavis;

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
 * 构建期可选 Plugin 模块的依赖方向守卫。
 *
 * <p>Plugin 是可选依赖，因此必须保持两个方向同时成立：模块内部只用 JDK、Jackson 与 Harness 的 schema / tool 契约，不依赖 Spring、数据库或
 * Platform；仓库其他模块（尤其 web）不在源码中引用 Plugin，只通过 runtime dependency 决定是否把它装进 发行物。这样删除 {@code
 * web/pom.xml} 的 runtime dependency 后应用不会残留任何 Plugin 语义。
 */
class MavisPluginModuleArchitectureTest {

  private static final String PLUGIN_PACKAGE = "fun.fengwk.kkstudio.plugin.minimaxmavis";
  private static final String PLUGIN_ARTIFACT = "kk-studio-plugin-minimax-mavis";
  private static final List<String> EXPECTED_PRODUCTION_DEPENDENCIES =
      List.of(
          "com.fasterxml.jackson.core:jackson-databind",
          "fun.fengwk.kk-studio:kk-studio-harness-common",
          "fun.fengwk.kk-studio:kk-studio-harness-contributor-api",
          "fun.fengwk.kk-studio:kk-studio-harness-tool",
          "fun.fengwk.kk-studio:kk-studio-platform",
          "org.springframework.boot:spring-boot-autoconfigure");
  private static final Set<String> ALLOWED_TEST_DEPENDENCIES =
      Set.of(
          "fun.fengwk.convention4j:convention4j-spring-boot-starter-test",
          "org.junit.jupiter:junit-jupiter",
          "org.mockito:mockito-core");

  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "com.fasterxml.jackson.",
          "lombok.",
          "org.springframework.beans.",
          "org.springframework.boot.autoconfigure.",
          "org.springframework.context.annotation.",
          "fun.fengwk.kkstudio.harness.contributor.api.",
          "fun.fengwk.kkstudio.harness.tool.",
          "fun.fengwk.kkstudio.harness.common.",
          "fun.fengwk.kkstudio.platform.plugin.",
          "fun.fengwk.kkstudio.plugin.minimaxmavis.");

  /** 模块主源码只允许白名单中的合法依赖包，不出现未经允许的外部或平台内部实现包。 */
  @Test
  void pluginMainSourcesStayOnJdkJacksonAndHarnessContracts() throws IOException {
    Path main = repositoryRoot().resolve("plugins/minimax-mavis/src/main/java");
    List<String> violations = new ArrayList<>();
    for (Path source : javaSources(main)) {
      for (String line : Files.readString(source, StandardCharsets.UTF_8).split("\\R")) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        String imported = trimmed.substring("import ".length()).replace(";", "").trim();
        if (imported.startsWith("static ")) {
          imported = imported.substring("static ".length()).trim();
        }
        boolean allowed = false;
        for (String prefix : ALLOWED_IMPORT_PREFIXES) {
          if (imported.startsWith(prefix)) {
            allowed = true;
            break;
          }
        }
        if (!allowed) {
          violations.add(main.relativize(source) + ": " + trimmed);
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "forbidden imports:\n" + String.join("\n", violations));
  }

  /** 模块 POM 的生产依赖集合精确等于白名单契约，test 依赖只允许白名单范围。 */
  @Test
  void pluginPomDeclaresOnlyClientDependencies() throws IOException {
    String pom =
        Files.readString(
            repositoryRoot().resolve("plugins/minimax-mavis/pom.xml"), StandardCharsets.UTF_8);
    List<String> violations = new ArrayList<>();
    List<String> productionDependencies = new ArrayList<>();
    List<String> testDependencies = new ArrayList<>();
    for (String dependency : dependencyBlocks(pom)) {
      String coordinate = tag(dependency, "groupId") + ":" + tag(dependency, "artifactId");
      if ("test".equals(tag(dependency, "scope"))) {
        testDependencies.add(coordinate);
        if (!ALLOWED_TEST_DEPENDENCIES.contains(coordinate)) {
          violations.add("test dependency " + coordinate);
        }
        continue;
      }
      productionDependencies.add(coordinate);
      if (!EXPECTED_PRODUCTION_DEPENDENCIES.contains(coordinate)) {
        violations.add("production dependency " + coordinate);
      }
    }
    productionDependencies.sort(String::compareTo);
    assertTrue(violations.isEmpty(), () -> "unexpected dependencies: " + violations);
    assertEquals(EXPECTED_PRODUCTION_DEPENDENCIES, productionDependencies);
  }

  /** 仓库其他模块的源码都不引用 Plugin 包，因此移除 web 的 runtime dependency 不需要改任何源码。 */
  @Test
  void noRepositoryModuleImportsThePlugin() throws IOException {
    Path root = repositoryRoot();
    List<String> violations = new ArrayList<>();
    for (String module : List.of("web", "platform", "canvas", "harness", "share", "schema")) {
      for (Path source : javaSources(root.resolve(module))) {
        if (Files.readString(source, StandardCharsets.UTF_8).contains(PLUGIN_PACKAGE)) {
          violations.add(root.relativize(source).toString());
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "reverse dependency on plugin: " + violations);
  }

  /** web 只以 runtime scope 选择 Plugin：没有它就无法装配 Plugin，有它也不会进入 web 的编译期契约。 */
  @Test
  void webSelectsPluginAtRuntimeScopeOnly() throws IOException {
    String pom = Files.readString(repositoryRoot().resolve("web/pom.xml"), StandardCharsets.UTF_8);
    String pluginDependency =
        dependencyBlocks(pom).stream()
            .filter(block -> block.contains("<artifactId>" + PLUGIN_ARTIFACT + "</artifactId>"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("web/pom.xml must select the plugin artifact"));

    assertTrue(pluginDependency.contains("<scope>runtime</scope>"));
    assertFalse(pluginDependency.contains("<scope>compile</scope>"));
  }

  private static List<String> dependencyBlocks(String pom) {
    List<String> blocks = new ArrayList<>();
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
    while (matcher.find()) {
      blocks.add(matcher.group(1));
    }
    return blocks;
  }

  private static String tag(String block, String name) {
    Matcher matcher =
        Pattern.compile("<" + name + ">\\s*([^<]+?)\\s*</" + name + ">").matcher(block);
    return matcher.find() ? matcher.group(1).trim() : null;
  }

  private static List<Path> javaSources(Path moduleRoot) throws IOException {
    if (!Files.isDirectory(moduleRoot)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(moduleRoot)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.toString().contains("/target/"))
          .sorted()
          .toList();
    }
  }

  /** 从模块工作目录向上定位仓库根，避免依赖固定的 surefire 工作目录深度。 */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("harness"))
          && Files.isDirectory(candidate.resolve("plugins"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("cannot locate repository root from " + Path.of(""));
  }
}
