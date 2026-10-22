package fun.fengwk.kkstudio.platform.catalog.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;

/**
 * 动态 MCP catalog 装配。
 *
 * <p>工具查找与执行都先向 {@link RuntimeToolCatalogSource} 取一次租用：Harness 贡献来自租用钉住的 Plugin 快照，MCP 贡献由本 catalog
 * 现读。source 依赖插件运行时，由 web 组合根与插件快照一起装配。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolCatalogConfiguration {

  @Bean(name = "mcpToolCatalog")
  @ConditionalOnMissingBean(name = "mcpToolCatalog")
  public McpToolCatalog mcpToolCatalog(McpServerRepository repository) {
    return new McpToolCatalog(repository);
  }
}
