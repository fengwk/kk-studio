package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;

/**
 * {@link RuntimeToolCatalogConfiguration} 的 Spring 装配测试：平台只发布动态 MCP 目录；需要插件运行时的租用 source 由 Web
 * 组合根装配。
 */
class RuntimeToolCatalogConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(McpServerRepository.class, () -> mock(McpServerRepository.class))
          .withUserConfiguration(RuntimeToolCatalogConfiguration.class);

  @Test
  void exposesOnlyTheDynamicMcpCatalog() {
    // 意图：唯一的 RuntimeToolCatalog 就是动态 MCP 目录，平台不自行组合 source，也没有静态目录单例。
    runner.run(
        context -> {
          assertEquals(1, context.getBeansOfType(RuntimeToolCatalog.class).size());
          assertSame(context.getBean("mcpToolCatalog"), context.getBean(RuntimeToolCatalog.class));
          assertTrue(context.getBeansOfType(RuntimeToolCatalogSource.class).isEmpty());
        });
  }

  @Test
  void unrelatedConcreteCatalogBeansDoNotSuppressTheNamedMcpCatalog() {
    // 意图：同类型扩展 bean 不得误触发条件装配并移除按名称依赖的内置 MCP 目录。
    McpToolCatalog otherMcp = new McpToolCatalog(mock(McpServerRepository.class));

    runner
        .withBean("otherMcpToolCatalog", McpToolCatalog.class, () -> otherMcp)
        .run(context -> assertTrue(context.containsBean("mcpToolCatalog")));
  }
}
