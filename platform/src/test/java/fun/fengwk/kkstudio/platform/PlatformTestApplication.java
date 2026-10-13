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
 * <p>Platform 依赖宿主注入的 JSON mapper（迁移前由旧宿主与 HTTP profile 提供）：Platform 自身不声明 Jackson 自动配置，以免抢占 Web
 * 组合根的 Bean；测试上下文用本 Bean 复刻宿主实际注入的序列化 profile——禁用字母序（保持声明序）、省略 null、按时间戳写时间、Long 写为字符串。HTTP 的严格
 * wire 约束（重复键、DTO mixin 等）由 Web 层测试覆盖，不在此处重复。
 */
@SpringBootApplication
@Import(PlatformHarnessTestConfiguration.class)
public class PlatformTestApplication {

  /**
   * 复刻宿主注入的 JSON mapper profile，供依赖 {@code tools.jackson.databind.ObjectMapper} 的 Platform Bean 装配。
   *
   * <p>只保留旧宿主与 Web 实际生效的差异项：Jackson 3 默认按字母序输出，这里显式关闭以维持声明序；不额外添加 Platform 自行发明的约束。
   */
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
