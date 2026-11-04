package fun.fengwk.kkstudio.canvas.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Canvas HTTP 单测与 wire golden 使用的严格 JSON mapper。
 *
 * <p>生产 mapper 由 Web 组合根装配（Boot 4 Jackson 3 + {@code StrictJacksonConfiguration}），Canvas 模块不依赖 Web，
 * 因此这里按同一组影响 Canvas wire 的设置独立构造：重复键检测、Long 写成十进制字符串、默认省略 null、不按属性名排序、 Instant 写时间戳。写出的 JSON
 * 形态与生产一致，断言不会因为模块边界而放宽。
 */
final class CanvasHttpJsonTestSupport {

  private static final JsonMapper MAPPER = create();

  private CanvasHttpJsonTestSupport() {}

  static JsonMapper get() {
    return MAPPER;
  }

  /** 与生产 HTTP 消息转换器同型的 3.x Jackson 转换器，供 standalone MockMvc 直接使用。 */
  static JacksonJsonHttpMessageConverter converter() {
    return new JacksonJsonHttpMessageConverter(MAPPER);
  }

  private static JsonMapper create() {
    SimpleModule longToStringModule = new SimpleModule("LongToStringModule");
    longToStringModule.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longToStringModule.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    return JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .changeDefaultPropertyInclusion(
            incl ->
                incl.withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .addModule(longToStringModule)
        .build();
  }
}
