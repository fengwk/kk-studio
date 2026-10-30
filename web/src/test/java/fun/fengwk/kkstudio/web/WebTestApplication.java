package fun.fengwk.kkstudio.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

/**
 * web 模块的 Spring Boot 测试入口。
 *
 * <p>集成套件通过 PostgreSQL 序列锁定持久化 id，并通过 notification adapter 验证 live overlay。
 *
 * <p>提供测试专用 {@code S3Client} mock 假件，使启动期 S3 readiness probe 顺利通过而不进行真实网络调用。
 */
@SpringBootApplication
public class WebTestApplication {

  @Bean
  @Primary
  public S3Client testS3Client() {
    S3Client client = mock(S3Client.class);
    when(client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(HeadBucketResponse.builder().build());
    return client;
  }

  public static void main(String[] args) {
    SpringApplication.run(WebTestApplication.class, args);
  }
}
