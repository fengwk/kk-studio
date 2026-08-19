package fun.fengwk.kkstudio.core.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;

import java.sql.SQLException;

/**
 * S3 场景集成测试的 PostgreSQL 支持：在 {@link PostgresSpringTestSupport} baseline 之后、任何 Spring 上下文创建之前把
 * {@code system_setting.storageMedia.s3Enabled} 置为 true，使 S3 服务族真正 flush 装配（而非返回 null）。
 */
public abstract class S3PostgresSpringTestSupport extends PostgresSpringTestSupport {

  @DynamicPropertySource
  static void enableS3BeforeContextCreation(DynamicPropertyRegistry ignored) {
    try {
      enableS3InSystemSettings();
    } catch (SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
