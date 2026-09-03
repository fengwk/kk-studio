package fun.fengwk.kkstudio.platform.harness.tool;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;

import java.util.List;

/**
 * 生产 {@link RuntimeToolCatalog} 聚合装配。
 *
 * <p>暴露包装静态 {@link HarnessCatalog} 的 {@link HarnessToolCatalogAdapter} 与动态读库的 {@link
 * McpToolCatalog}，并通过唯一标注 {@link Primary} 的复合 {@link RuntimeToolCatalog}
 * 提供确定性（静态在前、动态在后）且无缓存的运行时聚合。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeToolCatalogConfiguration {

  @Bean(name = "harnessToolCatalogAdapter")
  @ConditionalOnMissingBean(name = "harnessToolCatalogAdapter")
  public HarnessToolCatalogAdapter harnessToolCatalogAdapter(HarnessCatalog harnessCatalog) {
    return new HarnessToolCatalogAdapter(harnessCatalog);
  }

  @Bean(name = "mcpToolCatalog")
  @ConditionalOnMissingBean(name = "mcpToolCatalog")
  public McpToolCatalog mcpToolCatalog(
      McpServerRepository repository, McpToolClientFactory clientFactory) {
    return new McpToolCatalog(repository, clientFactory);
  }

  @Primary
  @Bean(name = "runtimeToolCatalog")
  @ConditionalOnMissingBean(name = "runtimeToolCatalog")
  public RuntimeToolCatalog runtimeToolCatalog(
      @Qualifier("harnessToolCatalogAdapter") HarnessToolCatalogAdapter harnessAdapter,
      @Qualifier("mcpToolCatalog") McpToolCatalog mcpCatalog) {
    return new CompositeRuntimeToolCatalog(List.of(harnessAdapter, mcpCatalog));
  }
}
