package fun.fengwk.kkstudio.canvas.infra.platform;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.testing.TestCanvasFunctionCatalogSource;

/**
 * 在 Platform 测试宿主上装配 Canvas 运行时的测试配置。
 *
 * <p>生产目录 source 由 web 组合根基于插件运行时提供；测试上下文按容器内 adapter 冻结固定目录，并用 Mockito 替身满足 Canvas runtime 的构造依赖。
 */
@TestConfiguration(proxyBeanMethods = false)
public class CanvasPlatformTestConfiguration {

  /** 按容器内 adapter 冻结的固定目录 source。 */
  @Bean
  PluginCanvasFunctionCatalogSource canvasFunctionCatalogSource(
      ObjectProvider<CanvasFunctionAdapter> adapters) {
    return new TestCanvasFunctionCatalogSource(
            CanvasFunctionCatalog.from(adapters.orderedStream().toList()))
        .source();
  }

  /**
   * Function worker executor 依赖的上下文快照工厂。
   *
   * <p>生产 Bean 由 web 组合根提供。Platform 测试宿主不含 web，这里用官方工厂和隔离注册表补齐。
   */
  @Bean
  public ContextSnapshotFactory canvasTestContextSnapshotFactory() {
    return ContextSnapshotFactory.builder()
        .contextRegistry(new ContextRegistry())
        .clearMissing(true)
        .build();
  }
}
