package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.catalog.tool.CompositeRuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.GlobalStorageToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;

/** 验证 BuiltinHarnessContributor 是 Spring bean，并可通过唯一的 HarnessCatalog 统一解析。 */
class BuiltinHarnessContributorWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<HarnessContributor> contributors;
  @Autowired private BuiltinHarnessContributor builtinContributor;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private ReadTool readTool;
  @Autowired private TaskTool taskTool;
  @Autowired private ApplicationContext context;

  @Test
  void componentScanWiresMovedCatalogAndResourceBeansExactlyOnce() {
    // 意图：真实 Platform 全上下文同时扫描 storage 与 harness，验证迁移未留下重复 bean 或漏掉新的组合根。
    assertEquals(1, context.getBeansOfType(ProviderResourceMaterializer.class).size());
    assertEquals(1, context.getBeansOfType(ToolResultHistoryMaterializer.class).size());
    assertSame(
        context.getBean("globalStorageToolResultHistoryMaterializer"),
        context.getBean(ToolResultHistoryMaterializer.class));
    assertInstanceOf(
        GlobalStorageToolResultHistoryMaterializer.class,
        context.getBean(ToolResultHistoryMaterializer.class));
    assertEquals(3, context.getBeansOfType(RuntimeToolCatalog.class).size());
    assertInstanceOf(CompositeRuntimeToolCatalog.class, context.getBean(RuntimeToolCatalog.class));
    assertTrue(context.getBean(RuntimeToolCatalog.class).findTool(ReadTool.NAME).isPresent());
  }

  @Test
  void registersBuiltinContributorAndExposesToolsThroughCatalog() {
    assertTrue(contributors.contains(builtinContributor));
    assertEquals(BuiltinHarnessContributor.ID, builtinContributor.descriptor().id());
    assertEquals(ReadTool.NAME, readTool.descriptor().name());
    assertEquals(TaskTool.NAME, taskTool.descriptor().name());

    assertTrue(harnessCatalog.findTool(ReadTool.NAME).isPresent());
    assertTrue(harnessCatalog.findTool(TaskTool.NAME).isPresent());
    assertTrue(harnessCatalog.findTool("update_goal").isPresent());
    // Goal 正文由用户维护：内建 catalog 不再暴露任何 Goal 创建工具。
    assertTrue(harnessCatalog.findTool("create_goal").isEmpty());

    assertEquals(
        ReadTool.NAME,
        harnessCatalog.findTool(ReadTool.NAME).orElseThrow().definition().descriptor().name());
    assertEquals(
        TaskTool.NAME,
        harnessCatalog.findTool(TaskTool.NAME).orElseThrow().definition().descriptor().name());
    assertTrue(harnessCatalog.findTool("test_missing_tool").isEmpty());
  }
}
