package fun.fengwk.kkstudio.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;

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
      // Share DTO 的严格 protocolOptionsJson 规则用 Jackson 2 注解声明，platform 的 Jackson 2 解析器仍然生效；
      // 但 Web 的 HTTP mapper 是 Jackson 3，不识别该注解，会把数字/布尔 token 强制转换成 String。
      // 这里用 mixin 把同一约束装回真实 wire 路径，且只作用于该字段，不改变其它契约的默认 coercion。
      builder.addMixIn(AgentModelVariantDTO.class, ProtocolOptionsJsonMixin.class);
    };
  }

  /** 只给 {@code protocolOptionsJson} 指定严格 String 反序列化器，其它属性注解继续由 DTO 自身提供。 */
  abstract static class ProtocolOptionsJsonMixin {

    @JsonDeserialize(using = ProtocolOptionsJsonStrictDeserializer.class)
    String protocolOptionsJson;
  }

  /** 数字/布尔/对象/数组 token 一律拒绝：string 字段绝不做标量强制转换。 */
  static final class ProtocolOptionsJsonStrictDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
        throws JacksonException {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw DatabindException.from(parser, "protocolOptionsJson must be a JSON string");
      }
      return parser.getText();
    }
  }
}
