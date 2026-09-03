package fun.fengwk.kkstudio.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** HTTP JSON 全局拒绝 duplicate field；各 DTO/codec 继续分别拒绝未知字段。 */
@Configuration(proxyBeanMethods = false)
public class StrictJacksonConfiguration {

  @Bean
  public JsonMapperBuilderCustomizer strictDuplicateFieldCustomizer() {
    return builder -> {
      builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
      builder.enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
      SimpleModule longToStringModule = new SimpleModule("LongToStringModule");
      longToStringModule.addSerializer(Long.class, new ToStringSerializer(Long.class));
      longToStringModule.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
      builder.addModule(longToStringModule);
    };
  }
}
