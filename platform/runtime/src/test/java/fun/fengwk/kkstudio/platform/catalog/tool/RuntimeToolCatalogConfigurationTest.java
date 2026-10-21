package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;

/**
 * {@link RuntimeToolCatalogConfiguration} 的 Spring 装配测试：只暴露动态 MCP 目录与唯一的 epoch 租用
 * source，且不再发布任何静态目录单例。
 */
class RuntimeToolCatalogConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(PluginCatalogRuntime.class, () -> mock(PluginCatalogRuntime.class))
          .withBean(McpServerRepository.class, () -> mock(McpServerRepository.class))
          .withUserConfiguration(RuntimeToolCatalogConfiguration.class);

  @Test
  void exposesSingleEpochBoundSourceAndDynamicMcpCatalog() {
    // 意图：验证上下文中只暴露一个 runtimeToolCatalogSource，且它就是 PluginRuntimeToolCatalogSource
    runner.run(
        context -> {
          assertTrue(context.containsBean("mcpToolCatalog"));
          assertTrue(context.containsBean("runtimeToolCatalogSource"));
          assertEquals(1, context.getBeansOfType(RuntimeToolCatalogSource.class).size());

          RuntimeToolCatalogSource source = context.getBean(RuntimeToolCatalogSource.class);
          assertSame(context.getBean("runtimeToolCatalogSource"), source);
          assertInstanceOf(PluginRuntimeToolCatalogSource.class, source);

          // 静态目录单例已被删除：不得再出现按当前目录无 epoch 解析的执行入口。
          assertFalse(context.containsBean("harnessToolCatalogAdapter"));
          assertFalse(context.containsBean("runtimeToolCatalog"));
          // 唯一的 RuntimeToolCatalog bean 就是动态 MCP 目录本身：不再有把静态目录与它聚合起来的执行入口。
          assertEquals(1, context.getBeansOfType(RuntimeToolCatalog.class).size());
          assertSame(context.getBean("mcpToolCatalog"), context.getBean(RuntimeToolCatalog.class));
        });
  }

  @Test
  void unrelatedConcreteCatalogBeansDoNotSuppressRequiredNamedSources() {
    // 意图：同类型扩展 bean 不得误触发条件装配并移除按名称依赖的内置 source。
    McpToolCatalog otherMcp = new McpToolCatalog(mock(McpServerRepository.class));

    runner
        .withBean("otherMcpToolCatalog", McpToolCatalog.class, () -> otherMcp)
        .run(
            context -> {
              assertTrue(context.containsBean("mcpToolCatalog"));
              assertTrue(context.containsBean("runtimeToolCatalogSource"));
              assertInstanceOf(
                  PluginRuntimeToolCatalogSource.class,
                  context.getBean(RuntimeToolCatalogSource.class));
            });
  }
}
