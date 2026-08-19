package fun.fengwk.kkstudio.web.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.sql.SQLException;

/**
 * S3 场景 HTTP 测试的 PostgreSQL 支持：在 {@link WebPostgresTestSupport} baseline 之后、任何 Spring 上下文创建之前把
 * {@code system_setting.storageMedia.s3Enabled} 置为 true，使 S3 服务族真正 flush 装配（而非返回 null）。
 */
public abstract class S3WebPostgresTestSupport extends WebPostgresTestSupport {

  @DynamicPropertySource
  static void enableS3BeforeContextCreation(DynamicPropertyRegistry ignored) {
    try {
      enableS3InSystemSettings();
    } catch (SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
