package fun.fengwk.kkstudio.web;

import com.fasterxml.jackson.core.JsonParser;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP JSON 全局拒绝 duplicate field；各 DTO/codec 继续分别拒绝未知字段。 */
@Configuration(proxyBeanMethods = false)
public class StrictJacksonConfiguration {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictDuplicateFieldCustomizer() {
    return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }
}
