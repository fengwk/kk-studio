package fun.fengwk.kkstudio.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** HTTP JSON 全局配置：严格拒绝重复键、默认省略 null、按 DTO 声明顺序输出、写 long 时间戳并将 Long 序列化为字符串。 */
@Configuration(proxyBeanMethods = false)
public class StrictJacksonConfiguration {

  @Bean
  public JsonMapperBuilderCustomizer httpJsonCustomizer() {
    return builder -> {
      builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
      builder.enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
      builder.changeDefaultPropertyInclusion(
          incl ->
              incl.withValueInclusion(JsonInclude.Include.NON_NULL)
                  .withContentInclusion(JsonInclude.Include.NON_NULL));
      builder.disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
      SimpleModule longToStringModule = new SimpleModule("LongToStringModule");
      longToStringModule.addSerializer(Long.class, new ToStringSerializer(Long.class));
      longToStringModule.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
      builder.addModule(longToStringModule);
    };
  }
}
