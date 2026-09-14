package fun.fengwk.kkstudio.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

/**
 * Platform 模块的 Spring Boot 测试入口。
 *
 * <p>Platform 不是 Harness 的组合根：生产 {@code EnvironmentSessionListener} 由 web 模块提供，因此测试上下文通过 {@link
 * PlatformHarnessTestConfiguration} 导入，使环境网关能用 no-op READY 桥接启动。
 *
 * <p>提供测试专用 {@code S3Client} mock 假件，使启动期 S3 readiness probe 顺利通过而不进行真实网络调用。
 */
@SpringBootApplication
@Import(PlatformHarnessTestConfiguration.class)
public class PlatformTestApplication {

  @Bean
  @Primary
  public S3Client testS3Client() {
    S3Client client = mock(S3Client.class);
    when(client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(HeadBucketResponse.builder().build());
    return client;
  }

  public static void main(String[] args) {
    SpringApplication.run(PlatformTestApplication.class, args);
  }
}
