package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 架构守卫：验证 Platform 关键消费方（{@code DatabaseTurnResolver}、{@code ToolExecutionGateway}、 {@code
 * ToolCatalogQueryService}、{@code AgentDefinitionConfigValidator}）均直接使用 {@code HarnessCatalog}，
 * 且仅通过 Contributor API 访问冻结目录。
 */
class HarnessCatalogArchitectureTest {

  @Test
  void platformConsumersDirectlyUseHarnessCatalog() throws IOException {
    Path main = locatePlatformMainJava();
    List<Path> consumers =
        List.of(
            main.resolve(
                "fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java"),
            main.resolve(
                "fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java"),
            main.resolve(
                "fun/fengwk/kkstudio/platform/catalog/definition/service/impl/"
                    + "AgentDefinitionConfigValidator.java"),
            main.resolve("fun/fengwk/kkstudio/platform/harness/tool/ToolCatalogQueryService.java"));

    for (Path consumer : consumers) {
      assertTrue(Files.isRegularFile(consumer), "consumer file must exist: " + consumer);
      String source = Files.readString(consumer, StandardCharsets.UTF_8);
      assertTrue(
          source.contains("import " + HarnessCatalog.class.getName() + ";"),
          consumer + " must import HarnessCatalog");
      assertTrue(
          source.contains("HarnessCatalog"), consumer + " must directly reference HarnessCatalog");
    }
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
