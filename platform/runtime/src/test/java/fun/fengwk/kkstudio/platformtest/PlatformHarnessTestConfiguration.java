package fun.fengwk.kkstudio.platformtest;

import static org.mockito.Mockito.mock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinAgentDriver;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ModelRequestDebugService;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.builtin.realtime.HarnessStreamCatchupService;

/**
 * Platform 测试宿主的能力目录与 Harness 装配基座。
 *
 * <p>生产 {@link EnvironmentSessionListener} 由 web 组合根（dispatcher wake）提供；platform 不再是组合根后， 测试上下文用
 * no-op 桥接满足 {@code EnvironmentDaemonGateway} 的构造依赖。internal Thread change source 同样由 web 组合根以平台
 * {@code HarnessChangeNotifications} bean 提供；本测试上下文提供不产生信号的占位 bean 满足构造依赖——platform 测试从不真正等待任务完成。
 *
 * <p>Harness 目录由测试宿主冻结已注册贡献，生产目录仍只由 web 组合根提供。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformHarnessTestConfiguration {

  /** 目录测试使用真实 Builtin Driver 校验；测试宿主不执行其 turn 与存储端口。 */
  @Bean
  @ConditionalOnMissingBean(DriverRegistry.class)
  public DriverRegistry platformDriverRegistry() {
    return new DriverRegistry(
        new BuiltinAgentDriver(mock(BuiltinStore.class), mock(TurnResolver.class)));
  }

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
  public RuntimeToolCatalogSource runtimeToolCatalogSource(
      HarnessCatalog harnessCatalog, McpToolCatalog mcpCatalog) {
    return () -> TestRuntimeToolCatalogSource.of(harnessCatalog, mcpCatalog).acquire();
  }

  /**
   * 结算扫描器的锁定复核依赖 Runtime store；platform 测试上下文不装配 PostgreSQL store，这里给出占位 bean 仅为满足装配 （platform
   * 测试从不触发结算，真实结算路径由 web 的 PostgreSQL 集成测试覆盖）。
   */
  @Bean
  @ConditionalOnMissingBean(HarnessStore.class)
  public HarnessStore platformHarnessStore() {
    return mock(HarnessStore.class);
  }

  /**
   * 等待环境线程投影读取 Harness Work 事实；生产 Runtime 由 web 组合根提供，platform 测试上下文给出占位 bean 仅为满足装配 （platform
   * 测试不执行等待环境投影，真实投影由 web 的 PostgreSQL 集成测试覆盖）。
   */
  @Bean
  @ConditionalOnMissingBean(HarnessRuntime.class)
  public HarnessRuntime platformHarnessRuntime() {
    return mock(HarnessRuntime.class);
  }

  /**
   * Thread Debug 服务由 web 组合根装配（需要完整的 Harness 规划链）；platform 测试上下文给出占位 bean 仅为满足 {@code
   * HarnessModelRequestDebugController} 的构造依赖，真实投影由 service 自身与 web 的测试覆盖。
   */
  @Bean
  @ConditionalOnMissingBean(ModelRequestDebugService.class)
  public ModelRequestDebugService platformModelRequestDebugService() {
    return mock(ModelRequestDebugService.class);
  }

  /**
   * Provider 请求预览服务同样由 web 组合根装配；platform 测试上下文用占位 bean 满足 {@code HarnessProviderRequestController}
   * 的构造依赖，真实预览由 service 自身与 web 的测试覆盖。
   */
  @Bean
  @ConditionalOnMissingBean(ProviderRequestPreviewService.class)
  public ProviderRequestPreviewService platformProviderRequestPreviewService() {
    return mock(ProviderRequestPreviewService.class);
  }

  /**
   * 活动模型请求追帧服务由 web 组合根装配（需要 Dispatcher 与 cluster 传输）；platform 测试上下文用占位 bean 满足 {@code
   * HarnessThreadStreamCatchupController} 的构造依赖。
   */
  @Bean
  @ConditionalOnMissingBean(HarnessStreamCatchupService.class)
  public HarnessStreamCatchupService platformHarnessStreamCatchupService() {
    return mock(HarnessStreamCatchupService.class);
  }
}
