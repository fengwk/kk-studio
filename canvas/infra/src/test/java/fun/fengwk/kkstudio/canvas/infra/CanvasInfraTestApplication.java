package fun.fengwk.kkstudio.canvas.infra;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.canvas.CanvasBlobReleaser;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.infra.function.FixedCanvasFunctionCatalogSource;

import java.time.Clock;
import java.util.List;

/** Canvas Infra PostgreSQL 集成测试的最小 Spring Boot 组合根。 */
@SpringBootApplication
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

  /** Canvas 装配依赖宿主提供的时间来源；测试在此显式声明，不依赖测试类被组件扫描的隐式结果。 */
  @Bean
  Clock canvasTestClock() {
    return Clock.systemUTC();
  }
}
