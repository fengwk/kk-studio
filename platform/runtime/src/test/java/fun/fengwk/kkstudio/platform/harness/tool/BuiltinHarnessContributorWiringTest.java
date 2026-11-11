package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.spi.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;

/** 验证 BuiltinHarnessContributor 是 Spring bean，并可通过唯一的 HarnessCatalog 统一解析。 */
class BuiltinHarnessContributorWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<HarnessContributor> contributors;
  @Autowired private BuiltinHarnessContributor builtinContributor;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private List<Tool> builtinFileTools;
  @Autowired private TaskTool taskTool;
  @Autowired private ApplicationContext context;

  @Test
  void componentScanWiresMovedCatalogAndResourceBeansExactlyOnce() {
    // 意图：验证 Platform 全上下文同时扫描 storage 与 harness，确保相关 bean 恰好单实例装配。
    assertEquals(1, context.getBeansOfType(ProviderResourceMaterializer.class).size());
    assertEquals(1, context.getBeansOfType(ToolResultHistoryMaterializer.class).size());
    assertSame(
        context.getBean("globalStorageToolResultHistoryMaterializer"),
        context.getBean(ToolResultHistoryMaterializer.class));
    assertInstanceOf(
        GlobalStorageToolResultHistoryMaterializer.class,
        context.getBean(ToolResultHistoryMaterializer.class));
    assertEquals(1, context.getBeansOfType(RuntimeToolCatalogSource.class).size());
    RuntimeToolCatalogSource source = context.getBean(RuntimeToolCatalogSource.class);
    assertSame(context.getBean("runtimeToolCatalogSource"), source);
    try (RuntimeToolCatalogSource.Lease lease = source.acquire()) {
      assertTrue(lease.catalog().findTool(FileTool.READ_NAME).isPresent());
      assertTrue(lease.harnessCatalog().findTool(TaskTool.NAME).isPresent());
    }
  }

  @Test
  void registersBuiltinContributorAndExposesToolsThroughCatalog() {
    assertTrue(contributors.contains(builtinContributor));
    assertEquals(BuiltinHarnessContributor.ID, builtinContributor.descriptor().id());
    assertEquals(
        List.of("read", "write", "edit", "grep", "find"),
        builtinFileTools.stream().map(tool -> tool.descriptor().name()).toList());
    assertTrue(builtinFileTools.stream().allMatch(FileTool.class::isInstance));
    assertEquals(TaskTool.NAME, taskTool.descriptor().name());

    assertTrue(harnessCatalog.findTool(FileTool.READ_NAME).isPresent());
    assertTrue(harnessCatalog.findTool(TaskTool.NAME).isPresent());

    assertEquals(
        FileTool.READ_NAME,
        harnessCatalog.findTool(FileTool.READ_NAME).orElseThrow().definition().descriptor().name());
    assertEquals(
        TaskTool.NAME,
        harnessCatalog.findTool(TaskTool.NAME).orElseThrow().definition().descriptor().name());
    assertTrue(harnessCatalog.findTool("test_missing_tool").isEmpty());
  }
}
