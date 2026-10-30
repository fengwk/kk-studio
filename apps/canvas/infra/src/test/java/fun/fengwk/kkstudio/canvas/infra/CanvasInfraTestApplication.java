package fun.fengwk.kkstudio.canvas.infra;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.canvas.CanvasBlobReleaser;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.infra.function.FixedCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasPlatformAutoConfiguration;
import fun.fengwk.kkstudio.platform.PlatformAutoConfiguration;

import java.time.Clock;
import java.util.List;

/**
 * Canvas Infra PostgreSQL 集成测试的最小组合根：只装配 Canvas 运行时，不加载平台与平台适配。
 *
 * <p>同时标注 {@code @TestComponent}，Canvas 运行时的组件扫描（带 {@code TypeExcludeFilter}）因此不会在平台宿主测试里收录它。
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@TestComponent
@EnableAutoConfiguration(
    exclude = {PlatformAutoConfiguration.class, CanvasPlatformAutoConfiguration.class})
public class CanvasInfraTestApplication {

  /**
   * 能力目录由宿主组合；模块测试用可替换目录的替身，使「冻结绑定只按同一定义执行」可被真实断言。
   *
   * <p>生产 source Bean 由 Platform 组合根提供，本替身只服务模块测试。
   */
  @Bean
  FixedCanvasFunctionCatalogSource canvasFunctionCatalogSource(
      List<CanvasFunctionAdapter> adapters) {
    return new FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog.from(adapters));
  }

  /**
   * 宿主 Storage 的 Blob 引用释放端口。
   *
   * <p>生产实现由 Platform 注入；模块测试直接按 {@code storage_blob.ref_count} 语义释放，使「删除 Resource 行」与「释放 Blob
   * 引用」必须同事务完成的事实可被真实断言，而不是用 mock 掩盖。
   */
  @Bean
  CanvasBlobReleaser canvasBlobReleaser(JdbcTemplate jdbc) {
    return blobId -> {
      // 与 Storage releaseOnce 的单语句不变式一致：减到 0 的同一语句内切换到 DELETING。
      int updated =
          jdbc.update(
              "update storage_blob set ref_count = ref_count - 1,"
                  + " state = case when ref_count - 1 = 0 then 'DELETING' else state end"
                  + " where id = ? and state = 'ACTIVE' and ref_count >= 1",
              blobId);
      if (updated != 1) {
        throw new IllegalStateException("cannot release unreferenced storage blob: " + blobId);
      }
    };
  }

  /** 生产 Blob 事实由平台适配提供；模块测试用可写入事实的替身。 */
  @Bean
  TestBlobAccess testBlobAccess() {
    return new TestBlobAccess();
  }

  /** Canvas 装配依赖宿主提供的时间来源；测试在此显式声明，不依赖测试类被组件扫描的隐式结果。 */
  @Bean
  Clock canvasTestClock() {
    return Clock.systemUTC();
  }

  /**
   * Function worker executor 依赖的上下文快照工厂。
   *
   * <p>生产 Bean 由 web 组合根提供。模块测试使用官方工厂和隔离注册表，不改写进程级 ContextRegistry。
   */
  @Bean
  ContextSnapshotFactory canvasTestContextSnapshotFactory() {
    return ContextSnapshotFactory.builder()
        .contextRegistry(new ContextRegistry())
        .clearMissing(true)
        .build();
  }
}
