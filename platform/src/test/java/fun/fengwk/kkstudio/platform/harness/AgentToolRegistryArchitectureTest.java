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

/** 统一 Agent Tool registry 的调用方不得重新引入已删除的旧目录或直接读取 Environment catalog。 */
class AgentToolRegistryArchitectureTest {

  private static final Pattern FORBIDDEN_CATALOG_REFERENCE =
      Pattern.compile("\\b(?:ToolCatalog|ToolContributionCatalog|EnvironmentToolCatalog)\\b");

  @Test
  void registryConsumersOnlyUseAgentToolRegistry() throws IOException {
    Path main = locatePlatformMainJava();
    List<Path> consumers =
        List.of(
            main.resolve(
                "fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java"),
            main.resolve(
                "fun/fengwk/kkstudio/platform/harness/tool/gateway/PlatformToolGateway.java"),
            main.resolve(
                "fun/fengwk/kkstudio/platform/catalog/definition/service/impl/"
                    + "AgentDefinitionConfigValidator.java"),
            main.resolve("fun/fengwk/kkstudio/platform/harness/tool/ToolCatalogQueryService.java"));
    List<String> violations = new ArrayList<>();
    for (Path consumer : consumers) {
      List<String> lines = Files.readAllLines(consumer, StandardCharsets.UTF_8);
      for (int index = 0; index < lines.size(); index++) {
        if (FORBIDDEN_CATALOG_REFERENCE.matcher(lines.get(index)).find()) {
          violations.add(consumer + ":" + (index + 1) + ": " + lines.get(index).trim());
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "registry consumers must not reference legacy catalogs:\n"
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
