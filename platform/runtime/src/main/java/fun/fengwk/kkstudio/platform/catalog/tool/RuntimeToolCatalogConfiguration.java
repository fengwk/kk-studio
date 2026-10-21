package fun.fengwk.kkstudio.platform.catalog.tool;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;

/**
 * 生产运行时工具目录装配：真实的动态 MCP catalog 与唯一的 {@link RuntimeToolCatalogSource}。
 *
 * <p>工具查找与执行都先向 source 取一次租用：Harness 贡献来自租用钉住的 Plugin 快照，MCP 贡献由动态 catalog 现读。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolCatalogConfiguration {

  @Bean(name = "mcpToolCatalog")
  @ConditionalOnMissingBean(name = "mcpToolCatalog")
  public McpToolCatalog mcpToolCatalog(McpServerRepository repository) {
    return new McpToolCatalog(repository);
  }

  @Bean(name = "runtimeToolCatalogSource")
  @ConditionalOnMissingBean(name = "runtimeToolCatalogSource")
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      PluginCatalogRuntime pluginCatalogRuntime,
      @Qualifier("mcpToolCatalog") McpToolCatalog mcpCatalog) {
    return new PluginRuntimeToolCatalogSource(pluginCatalogRuntime, mcpCatalog);
  }
}
