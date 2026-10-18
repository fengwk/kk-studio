package fun.fengwk.kkstudio.platform;

import static org.mockito.Mockito.mock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.runtime.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.project.controller.IssueControllerProperties;

/**
 * Platform 测试宿主的能力目录与 Harness 装配基座。
 *
 * <p>生产 {@link EnvironmentSessionListener} 由 web 组合根（dispatcher wake）提供；platform 不再是组合根后， 测试上下文用
 * no-op 桥接满足 {@code EnvironmentDaemonGateway} 的构造依赖。internal Thread change source 同样由 web
 * 组合根提供（PostgreSQL LISTEN 适配）；本测试上下文提供不产生信号的占位 bean 满足构造依赖——platform 测试从不真正等待任务完成。
 *
 * <p>两域目录由测试宿主冻结已注册贡献，生产目录仍只由 web 组合根提供。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformHarnessTestConfiguration {

  @Bean
  public EnvironmentSessionListener environmentSessionListener() {
    return ignoredEnvironmentId -> {};
  }

  @Bean
  public HarnessThreadChangeSource harnessThreadChangeSource() {
    return (threadId, onChange) -> () -> {};
  }

  @Bean
  public HarnessCatalog harnessCatalog(ObjectProvider<HarnessContributor> contributors) {
    return HarnessCatalog.from(contributors.orderedStream().toList());
  }

  /**
   * Platform 测试宿主的运行时工具目录 source：Harness 目录固定，MCP 每次租用现读并冻结。
   *
   * <p>生产 source 需要 web 组合根发布的 {@code PluginCatalogRuntime}；platform 测试上下文不装配插件运行时，因此测试宿主只冻结自己的
   * Harness 目录和实际 MCP 事实。冻结索引与严格校验仍复用生产实现，租用内不受后续 MCP 变化影响。
   */
  @Bean
  @ConditionalOnMissingBean(name = "runtimeToolCatalogSource")
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      HarnessCatalog harnessCatalog, McpToolCatalog mcpCatalog) {
    return () -> TestRuntimeToolCatalogSource.of(harnessCatalog, mcpCatalog).acquire();
  }

  /**
   * Platform 测试宿主的 Canvas 目录 source：按容器内 adapter 冻结固定目录，严格按完整定义绑定。
   *
   * <p>生产 source 由 web 组合根提供；测试上下文只求满足 canvas runtime 的构造依赖。
   */
  @Bean
  public CanvasFunctionCatalogSource canvasFunctionCatalogSource(
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(adapters.orderedStream().toList());
    return () ->
        new CanvasFunctionCatalogSource.Lease() {
          @Override
          public CanvasFunctionCatalog catalog() {
            return catalog;
          }

          @Override
          public CanvasFunctionBinding bind(String functionName) {
            return new CanvasFunctionBinding(catalog.require(functionName).function());
          }

          @Override
          public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
            CanvasFunctionCatalog.RegisteredFunction registered =
                catalog
                    .find(binding.definition().name())
                    .filter(found -> found.function().equals(binding.definition()))
                    .orElseThrow(
                        () -> new IllegalStateException("binding does not match the catalog"));
            return registered;
          }

          @Override
          public void close() {}
        };
  }

  /**
   * 结算扫描器的锁定复核依赖 Runtime store；platform 测试上下文不装配 harness/infra，这里给出占位 bean 仅为满足装配 （platform
   * 测试从不触发结算，真实结算路径由 web 的 PostgreSQL 集成测试覆盖）。
   */
  @Bean
  @ConditionalOnMissingBean(HarnessStore.class)
  public HarnessStore platformHarnessStore() {
    return mock(HarnessStore.class);
  }

  @Bean
  public IssueControllerProperties issueControllerProperties() {
    return new IssueControllerProperties();
  }
}
