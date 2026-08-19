package fun.fengwk.kkstudio.core.storage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 存储集成测试的 S3 假件：以内存对象与记录式预签名替换真实 S3 客户端， 使 reserve/complete/expiry/delete 全链路在 PostgreSQL 上以真实事务运行。
 *
 * <p>测试配置在自动配置之后处理，无法用 {@code @ConditionalOnMissingBean} 抑制真实实现， 因此假件使用独立 bean 名并声明
 * {@code @Primary} 覆盖注入；真实 S3 客户端只构造不联网，无副作用。
 *
 * <p>S3 启用的 {@code system_setting} 基线由 {@link S3PostgresSpringTestSupport} 在上下文创建前置位。
 *
 * @author fengwk
 */
@TestConfiguration
public class StorageS3TestConfiguration {

  @Bean
  @Primary
  public S3StorageService storageS3TestStorageService() {
    return new InMemoryS3StorageService();
  }

  @Bean
  @Primary
  public S3PresignService storageS3TestPresignService() {
    return new RecordingS3PresignService();
  }
}
