package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 统一 HarnessCatalog 的平台实现与调用方不得重新引入已删除的旧目录、旧注册表或插件源类型。 */
class HarnessCatalogArchitectureTest {

  private static final Pattern FORBIDDEN_LEGACY_CATALOG_OR_REGISTRY =
      Pattern.compile(
          "\\b(?:AgentToolRegistry|ToolCatalog|ToolContributionCatalog|EnvironmentToolCatalog|PluginCatalog|HarnessPluginSource|PluginBranchViewLoader|DatabasePluginBranchViewLoader|HarnessPlugin|PluginTool|PluginRegistrar)\\b");

  @Test
  void platformMainNeverReferencesLegacyCatalogsOrPluginSources() throws IOException {
    Path main = locatePlatformMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
      for (Path javaFile : javaFiles) {
        List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
          String line = lines.get(index);
          if (FORBIDDEN_LEGACY_CATALOG_OR_REGISTRY.matcher(line).find()) {
            violations.add(main.relativize(javaFile) + ":" + (index + 1) + ": " + line.trim());
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "platform main sources must not reference legacy catalogs, registries or plugin sources:\n"
                + String.join("\n", violations));
  }

  private static Path locatePlatformMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("platform/src/main/java"));
    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate platform main sources from " + cwd);
  }
}
