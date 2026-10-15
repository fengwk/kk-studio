package fun.fengwk.kkstudio.platform.catalog.tool;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;

import java.util.concurrent.ExecutorService;

/**
 * 生产运行时工具目录装配：真实的动态 MCP catalog 与唯一的 {@link RuntimeToolCatalogSource}。
 *
 * <p>不再发布任何静态 {@link RuntimeToolCatalog} 单例：工具查找与执行都先向 source 取一个 epoch 租用，Harness 贡献由该 epoch 的
 * Plugin 快照冻结，MCP 贡献由动态 catalog 现读。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolCatalogConfiguration {

  @Bean(name = "mcpToolCatalog")
  @ConditionalOnMissingBean(name = "mcpToolCatalog")
  public McpToolCatalog mcpToolCatalog(
      McpServerRepository repository,
      @Qualifier("toolGatewayExecutor") ExecutorService toolGatewayExecutor) {
    return new McpToolCatalog(repository, toolGatewayExecutor);
  }

  @Bean(name = "runtimeToolCatalogSource")
  @ConditionalOnMissingBean(name = "runtimeToolCatalogSource")
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      PluginCatalogRuntime pluginCatalogRuntime,
      @Qualifier("mcpToolCatalog") McpToolCatalog mcpCatalog) {
    return new PluginRuntimeToolCatalogSource(pluginCatalogRuntime, mcpCatalog);
  }
}
