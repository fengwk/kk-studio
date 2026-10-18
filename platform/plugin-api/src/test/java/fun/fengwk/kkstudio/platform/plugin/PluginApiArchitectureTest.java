package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plugin SPI 模块的依赖方向守卫。
 *
 * <p>plugin-api 是 Plugin JAR 与宿主共享的纯契约：主源码只允许 JDK、Jackson 3 值模型与 Harness/Canvas 的纯值契约，绝不依赖
 * Spring、数据库、HTTP 客户端或 Platform 宿主实现（{@code kk-studio-platform-runtime}）；POM 的生产依赖也必须精确匹配该集合。
 */
class PluginApiArchitectureTest {

  private static final String OWN_PACKAGE_PREFIX = "fun.fengwk.kkstudio.platform.plugin.";

  private static final List<String> ALLOWED_IMPORT_PREFIXES =
      List.of(
          "java.",
          "javax.",
          "tools.jackson.",
          "fun.fengwk.kkstudio.harness.common.",
          "fun.fengwk.kkstudio.harness.contributor.api.",
          "fun.fengwk.kkstudio.canvas.",
          OWN_PACKAGE_PREFIX);

  private static final List<String> EXPECTED_PRODUCTION_DEPENDENCIES =
      List.of(
          "fun.fengwk.kk-studio:kk-studio-canvas-plugin-api",
          "fun.fengwk.kk-studio:kk-studio-harness-common",
          "fun.fengwk.kk-studio:kk-studio-harness-contributor-api",
          "tools.jackson.core:jackson-databind");

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "org.springframework.",
          "java.sql.",
          "javax.sql.",
          "java.net.http.",
          "java.net." + "HttpURLConnection",
          "java.net." + "URLConnection",
          "fun.fengwk.kkstudio.platform.runtime.",
          OWN_PACKAGE_PREFIX + "PluginProperties",
          OWN_PACKAGE_PREFIX + "PluginConfiguration",
          OWN_PACKAGE_PREFIX + "credential.DatabasePluginCredentialStore",
          OWN_PACKAGE_PREFIX + "credential.PluginCredentialRefresh");

  /** 主源码只允许白名单中的纯契约依赖，宿主实现、Spring、数据库与 HTTP 客户端包前缀都会命中违规。 */
  @Test
  void pluginApiMainSourcesStayOnJdkAndHarnessCommonContracts() throws IOException {
    Path main = pluginApiMainJava();
    List<String> violations = new ArrayList<>();
    for (Path source : javaSources(main)) {
      for (String line : Files.readString(source, StandardCharsets.UTF_8).split("\\R")) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        String imported = normalizeImport(trimmed);
        if (!allowedImport(imported)) {
          violations.add(main.relativize(source) + ": " + trimmed);
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "forbidden imports:\n" + String.join("\n", violations));
  }

  /** API 模块的生产依赖精确等于纯契约集合，宿主实现与 Spring 依赖只能留在 runtime 叶。 */
  @Test
  void pluginApiDeclaresOnlyPureContractProductionDependencies() throws IOException {
    String pom = Files.readString(pluginApiPom(), StandardCharsets.UTF_8);
    List<String> production = new ArrayList<>();
    for (String dependency : dependencyBlocks(pom)) {
      if (!"test".equals(tag(dependency, "scope"))) {
        production.add(tag(dependency, "groupId") + ":" + tag(dependency, "artifactId"));
      }
    }
    production.sort(String::compareTo);
    assertEquals(EXPECTED_PRODUCTION_DEPENDENCIES, production);
  }

  @Test
  void jdkAndOwnPackagePrefixesDoNotAdmitDatabaseHttpOrRuntimeImplementations() {
    // JDK 和同名 package 白名单不能把数据库、网络客户端、Spring 或宿主实现误放进纯 API。
    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
      assertFalse(allowedImport(prefix + "Example"), prefix);
    }
    assertTrue(allowedImport(URI.class.getName()));
    assertTrue(allowedImport(PluginCredentialMaterial.class.getName()));
    assertTrue(allowedImport(JsonNode.class.getName()));
  }

  private static boolean allowedImport(String imported) {
    return ALLOWED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)
        && FORBIDDEN_IMPORT_PREFIXES.stream().noneMatch(imported::startsWith);
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
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

  private static List<Path> javaSources(Path root) throws IOException {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
    }
  }

  private static Path pluginApiMainJava() {
    return repositoryRoot().resolve("platform/plugin-api/src/main/java");
  }

  private static Path pluginApiPom() {
    return repositoryRoot().resolve("platform/plugin-api/pom.xml");
  }

  /** 从当前工作目录向上定位仓库根，同时校验 runtime 叶与 API 叶都已存在。 */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("platform/plugin-api/src/main/java"))
          && Files.isDirectory(candidate.resolve("platform/runtime/src/main/java"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "cannot locate repository root from " + Path.of("").toAbsolutePath().normalize());
  }
}
