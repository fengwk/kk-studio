package fun.fengwk.kkstudio.canvas.infra.platform;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.canvas.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;

/**
 * 在 Platform 测试宿主上装配 Canvas 运行时的测试配置。
 *
 * <p>生产目录 source 由 web 组合根基于插件运行时提供；测试上下文只按容器内 adapter 冻结固定目录，并严格按完整定义绑定，以满足 Canvas runtime 的构造依赖。
 */
@TestConfiguration(proxyBeanMethods = false)
public class CanvasPlatformTestConfiguration {

  /** 按容器内 adapter 冻结的固定目录 source。 */
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
