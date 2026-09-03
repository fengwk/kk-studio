package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;

/**
 * {@link RuntimeToolCatalogConfiguration} 的 Spring 装配测试： 验证两个源 bean（静态适配器与 MCP 动态目录）与唯一的 @Primary
 * 复合目录均成功暴露且未发生循环/歧义注入。
 */
class RuntimeToolCatalogConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(HarnessCatalog.class, () -> mock(HarnessCatalog.class))
          .withBean(McpServerRepository.class, () -> mock(McpServerRepository.class))
          .withBean(McpToolClientFactory.class, () -> mock(McpToolClientFactory.class))
          .withUserConfiguration(RuntimeToolCatalogConfiguration.class);

  @Test
  void exposesSourcesAndPrimaryCompositeBean() {
    // 意图：验证 Spring 上下文中正确暴露源 bean 与 primary aggregate RuntimeToolCatalog bean
    runner.run(
        context -> {
          assertTrue(context.containsBean("harnessToolCatalogAdapter"));
          assertTrue(context.containsBean("mcpToolCatalog"));
          assertTrue(context.containsBean("runtimeToolCatalog"));

          HarnessToolCatalogAdapter adapter = context.getBean(HarnessToolCatalogAdapter.class);
          McpToolCatalog mcp = context.getBean(McpToolCatalog.class);

          RuntimeToolCatalog primary = context.getBean(RuntimeToolCatalog.class);
          assertSame(context.getBean("runtimeToolCatalog"), primary);
          assertInstanceOf(CompositeRuntimeToolCatalog.class, primary);

          // 验证按具体类型仍可注入独立的源 bean
          assertSame(adapter, context.getBean("harnessToolCatalogAdapter"));
          assertSame(mcp, context.getBean("mcpToolCatalog"));
        });
  }

  @Test
  void unrelatedConcreteCatalogBeansDoNotSuppressRequiredNamedSources() {
    // 意图：同类型扩展 bean 不得误触发条件装配并移除聚合器按名称依赖的内置 source。
    HarnessToolCatalogAdapter otherAdapter =
        new HarnessToolCatalogAdapter(mock(HarnessCatalog.class));
    McpToolCatalog otherMcp =
        new McpToolCatalog(mock(McpServerRepository.class), mock(McpToolClientFactory.class));

    runner
        .withBean(
            "otherHarnessToolCatalogAdapter", HarnessToolCatalogAdapter.class, () -> otherAdapter)
        .withBean("otherMcpToolCatalog", McpToolCatalog.class, () -> otherMcp)
        .run(
            context -> {
              assertTrue(context.containsBean("harnessToolCatalogAdapter"));
              assertTrue(context.containsBean("mcpToolCatalog"));
              assertTrue(context.containsBean("runtimeToolCatalog"));
              assertInstanceOf(
                  CompositeRuntimeToolCatalog.class, context.getBean(RuntimeToolCatalog.class));
            });
  }
}
