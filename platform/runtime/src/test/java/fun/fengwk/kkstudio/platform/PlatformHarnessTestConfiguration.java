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
   * Platform 测试宿主的运行时工具目录 source：单 epoch 冻结目录，测试工具都是内置（pluginSource 为 null、无 MCP 工具）。
   *
   * <p>生产 source 需要 web 组合根发布的 {@code PluginCatalogRuntime}；platform 测试上下文不装配插件运行时，因此测试宿主只冻结自己的
   * Harness 目录。冻结索引与严格校验仍复用生产实现，测试替身不与生产语义漂移。
   */
  @Bean
  @ConditionalOnMissingBean(name = "runtimeToolCatalogSource")
  public RuntimeToolCatalogSource runtimeToolCatalogSource(HarnessCatalog harnessCatalog) {
    return TestRuntimeToolCatalogSource.of(
        harnessCatalog, TestRuntimeToolCatalogSource.emptyMcpCatalog());
  }

  /**
   * Platform 测试宿主的 Canvas 目录 source：单 epoch 冻结目录，测试 adapter 都是内置（pluginSource 为 null）。
   *
   * <p>生产 source 由 web 组合根提供；platform 不再是组合根后，测试上下文只求满足 canvas runtime 的构造依赖，因此这里只冻结固定 epoch 并严格按
   * epoch/完整定义绑定，不做插件来源推导。
   */
  @Bean
  public CanvasFunctionCatalogSource canvasFunctionCatalogSource(
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    return new SingleEpochCanvasFunctionCatalogSource(
        CanvasFunctionCatalog.from(adapters.orderedStream().toList()));
  }

  /** 测试替身：只暴露一个固定 epoch，bind 冻结内置来源，require 严格校验 epoch 与完整定义。 */
  private static final class SingleEpochCanvasFunctionCatalogSource
      implements CanvasFunctionCatalogSource {

    private static final long EPOCH = 1L;

    private final CanvasFunctionCatalog catalog;

    private SingleEpochCanvasFunctionCatalogSource(CanvasFunctionCatalog catalog) {
      this.catalog = catalog;
    }

    @Override
    public Lease acquireCurrent() {
      return acquire(EPOCH);
    }

    @Override
    public Lease acquire(long catalogEpoch) {
      if (catalogEpoch != EPOCH) {
        throw new IllegalArgumentException("catalog epoch is not available: " + catalogEpoch);
      }
      return new Lease() {
        @Override
        public long epoch() {
          return EPOCH;
        }

        @Override
        public CanvasFunctionCatalog catalog() {
          return catalog;
        }

        @Override
        public CanvasFunctionBinding bind(String functionName) {
          return new CanvasFunctionBinding(EPOCH, catalog.require(functionName).function(), null);
        }

        @Override
        public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
          if (binding.catalogEpoch() != EPOCH) {
            throw new IllegalArgumentException("binding epoch does not match the acquired catalog");
          }
          CanvasFunctionCatalog.RegisteredFunction registered =
              catalog.require(binding.definition().name());
          if (!registered.function().equals(binding.definition())
              || binding.pluginSource() != null) {
            throw new IllegalArgumentException("binding does not match the acquired catalog");
          }
          return registered;
        }

        @Override
        public void close() {}
      };
    }
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
