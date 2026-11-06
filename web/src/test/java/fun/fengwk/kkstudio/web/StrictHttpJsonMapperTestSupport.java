package fun.fengwk.kkstudio.web;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone HTTP 测试共享的 mapper，由 Boot4 与生产 {@link StrictJacksonConfiguration} 共同装配。 */
public final class StrictHttpJsonMapperTestSupport {

  private static final JsonMapper MAPPER = create();

  private StrictHttpJsonMapperTestSupport() {}

  public static JsonMapper get() {
    return MAPPER;
  }

  /** 消息转换器使用的严格输入配置；其他测试仍可取容器注入的 mapper。 */
  public static JsonMapper strictHttp() {
    return HttpBoundaryConfiguration.strictHttpMapper(MAPPER);
  }

  private static JsonMapper create() {
    AtomicReference<JsonMapper> reference = new AtomicReference<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(StrictJacksonConfiguration.class)
        .run(context -> reference.set(context.getBean(JsonMapper.class)));
    return Objects.requireNonNull(
        reference.get(), "strict HTTP Jackson 3 mapper must be available");
  }
}
