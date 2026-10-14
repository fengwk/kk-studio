package fun.fengwk.kkstudio.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Platform 模块的 Spring Boot 测试入口。
 *
 * <p>Platform 不是 Harness 的组合根：生产 {@code EnvironmentSessionListener} 由 web 模块提供，因此测试上下文通过 {@link
 * PlatformHarnessTestConfiguration} 导入，使环境网关能用 no-op READY 桥接启动。
 *
 * <p>提供测试专用 {@code S3Client} mock 假件，使启动期 S3 readiness probe 顺利通过而不进行真实网络调用。
 *
 * <p>Platform 集成测试由测试宿主提供 JSON mapper；HTTP wire 严格性由 Web 负责。
 */
@SpringBootApplication
@Import(PlatformHarnessTestConfiguration.class)
public class PlatformTestApplication {

  /** 测试宿主注入的 JSON mapper：禁用字母序（保持声明序）、省略 null、按时间戳写时间、Long 写为字符串。 */
  @Bean
  @Primary
  public JsonMapper testJsonMapper() {
    SimpleModule longToStringModule = new SimpleModule("LongToStringModule");
    longToStringModule.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longToStringModule.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    return JsonMapper.builder()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .changeDefaultPropertyInclusion(
            inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .addModule(longToStringModule)
        .build();
  }

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
