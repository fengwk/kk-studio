package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 架构守卫：验证 Platform 四大工具消费方（{@code DatabaseTurnResolver}、{@code ToolExecutionGateway}、 {@code
 * ToolCatalogQueryService}、{@code AgentDefinitionConfigValidator}）均使用 {@code RuntimeToolCatalog}
 * 进行工具查找与列出；仅在 Resolver/Gateway 中允许保留 {@code HarnessCatalog} 访问非工具元数据（context projectors 与 custom
 * entry types），并严禁通过 {@code HarnessCatalog} 进行工具查找。
 */
class HarnessCatalogArchitectureTest {

  @Test
  void fourConsumersUseRuntimeToolCatalogAndRestrictHarnessCatalog() throws IOException {
    // 意图：断言四大消费方统一使用 RuntimeToolCatalog 进行工具查找，严格禁止通过 HarnessCatalog 查找工具
    Path main = locatePlatformMainJava();

    Path resolverPath =
        main.resolve(
            "fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java");
    Path gatewayPath =
        main.resolve("fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java");
    Path validatorPath =
        main.resolve(
            "fun/fengwk/kkstudio/platform/catalog/definition/service/impl/"
                + "AgentDefinitionConfigValidator.java");
    Path queryServicePath =
        main.resolve("fun/fengwk/kkstudio/platform/harness/tool/ToolCatalogQueryService.java");

    List<Path> allConsumers = List.of(resolverPath, gatewayPath, validatorPath, queryServicePath);
    List<Path> externalPackageConsumers = List.of(resolverPath, gatewayPath, validatorPath);
    for (Path consumer : externalPackageConsumers) {
      assertTrue(Files.isRegularFile(consumer), "consumer file must exist: " + consumer);
      String source = Files.readString(consumer, StandardCharsets.UTF_8);
      assertTrue(
          source.contains("import " + RuntimeToolCatalog.class.getName() + ";"),
          consumer + " must import RuntimeToolCatalog");
    }
    for (Path consumer : allConsumers) {
      assertTrue(Files.isRegularFile(consumer), "consumer file must exist: " + consumer);
      String source = Files.readString(consumer, StandardCharsets.UTF_8);
      assertTrue(
          source.contains("RuntimeToolCatalog"), consumer + " must reference RuntimeToolCatalog");
    }

    // ToolCatalogQueryService 与 AgentDefinitionConfigValidator 不得使用 HarnessCatalog
    List<Path> toolOnlyConsumers = List.of(validatorPath, queryServicePath);
    for (Path consumer : toolOnlyConsumers) {
      String source = Files.readString(consumer, StandardCharsets.UTF_8);
      assertFalse(
          source.contains("import " + HarnessCatalog.class.getName() + ";"),
          consumer + " must not import HarnessCatalog");
      assertFalse(
          source.contains("HarnessCatalog"), consumer + " must not reference HarnessCatalog");
    }

    // DatabaseTurnResolver 与 ToolExecutionGateway 仅为非工具元数据保留 HarnessCatalog，严禁工具查找
    List<Path> metadataConsumers = List.of(resolverPath, gatewayPath);
    for (Path consumer : metadataConsumers) {
      String source = Files.readString(consumer, StandardCharsets.UTF_8);
      assertTrue(
          source.contains("import " + HarnessCatalog.class.getName() + ";"),
          consumer + " must import HarnessCatalog for metadata");
      assertFalse(
          source.contains("harnessCatalog.findTool"),
          consumer + " must not look up tools via harnessCatalog.findTool");
      assertFalse(
          source.contains("harnessCatalog.selectableTools"),
          consumer + " must not list tools via harnessCatalog.selectableTools");
      assertFalse(
          source.contains("harnessCatalog.tools()"),
          consumer + " must not list tools via harnessCatalog.tools()");
      assertFalse(
          source.contains("catalog.findTool"),
          consumer + " must not look up tools via catalog.findTool");
      assertFalse(
          source.contains("catalog.selectableTools"),
          consumer + " must not list tools via catalog.selectableTools");
      assertFalse(
          source.contains("catalog.tools()"),
          consumer + " must not list tools via catalog.tools()");
      assertTrue(
          source.contains("toolCatalog.findTool"),
          consumer + " must look up tools via toolCatalog.findTool");
    }

    // 确认保留的 HarnessCatalog 在两个消费方中具体使用的非工具元数据能力
    String resolverSource = Files.readString(resolverPath, StandardCharsets.UTF_8);
    assertTrue(
        resolverSource.matches("(?s).*harnessCatalog\\s*\\.contextProjectors\\(\\).*"),
        "DatabaseTurnResolver must use harnessCatalog.contextProjectors() for context projections");

    String gatewaySource = Files.readString(gatewayPath, StandardCharsets.UTF_8);
    assertTrue(
        gatewaySource.matches("(?s).*harnessCatalog\\s*\\.findCustomEntryType.*"),
        "ToolExecutionGateway must use harnessCatalog.findCustomEntryType for custom entry validation");
  }

  @Test
  void harnessCatalogImportsStayWithinStaticAdapterWiringAndMetadataConsumers() throws IOException {
    // 意图：新增 Platform 生产代码不得绕过 RuntimeToolCatalog 直接依赖 HarnessCatalog。
    Path main = locatePlatformMainJava();
    Set<Path> allowedImporters =
        Set.of(
            main.resolve(
                    "fun/fengwk/kkstudio/platform/harness/thread/command/DatabaseTurnResolver.java")
                .normalize(),
            main.resolve("fun/fengwk/kkstudio/platform/harness/tool/HarnessToolCatalogAdapter.java")
                .normalize(),
            main.resolve(
                    "fun/fengwk/kkstudio/platform/harness/tool/RuntimeToolCatalogConfiguration.java")
                .normalize(),
            main.resolve(
                    "fun/fengwk/kkstudio/platform/harness/tool/gateway/"
                        + "HarnessToolGatewayConfiguration.java")
                .normalize(),
            main.resolve(
                    "fun/fengwk/kkstudio/platform/harness/tool/gateway/ToolExecutionGateway.java")
                .normalize());

    String harnessCatalogImport = "import " + HarnessCatalog.class.getName() + ";";
    Set<Path> actualImporters;
    try (Stream<Path> paths = Files.walk(main)) {
      actualImporters =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".java"))
              .filter(path -> contains(path, harnessCatalogImport))
              .map(Path::normalize)
              .collect(Collectors.toUnmodifiableSet());
    }
    assertEquals(
        allowedImporters,
        actualImporters,
        "HarnessCatalog imports must stay limited to its adapter/wiring and two metadata consumers");
  }

  private static boolean contains(Path path, String expected) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8).contains(expected);
    } catch (IOException error) {
      throw new IllegalStateException("failed to inspect source: " + path, error);
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
