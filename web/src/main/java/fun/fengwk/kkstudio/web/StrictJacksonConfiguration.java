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

import fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAiRuntimeDTO;

import java.util.ArrayList;
import java.util.List;

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
      // 系统设置与 Provider create/update 的 HTTP 重试白名单是严格整数列表：数字 token 绝不做 float/字符串
      // 强制转换；Wire 层只保证 JSON 形态，范围/重复/不可变副本统一由 ModelHttpErrorPolicy 决定。
      builder.addMixIn(SystemSettingsAiRuntimeDTO.class, HttpRetryStatusListMixin.class);
      builder.addMixIn(AgentProviderEditablePropertiesDTO.class, HttpRetryStatusListMixin.class);
    };
  }

  /** 给 {@code modelHttpRetryStatusCodes} 指定严格整数列表反序列化器；只作用于该属性。 */
  abstract static class HttpRetryStatusListMixin {

    @JsonDeserialize(using = HttpRetryStatusListStrictDeserializer.class)
    List<Integer> modelHttpRetryStatusCodes;
  }

  /**
   * HTTP 重试白名单只接受 JSON 数组 + 整数元素；显式 null 保留为 null（继承/清除语义由领域层决定）。
   *
   * <p>拒绝 {@code 429.5}、{@code 429.0}、{@code "429"}、null 元素以及嵌套结构；范围/重复统一交给 {@link
   * ModelHttpErrorPolicy}。
   */
  static final class HttpRetryStatusListStrictDeserializer
      extends ValueDeserializer<List<Integer>> {

    @Override
    public List<Integer> deserialize(JsonParser parser, DeserializationContext context)
        throws JacksonException {
      if (parser.currentToken() == JsonToken.VALUE_NULL) {
        return null;
      }
      if (parser.currentToken() != JsonToken.START_ARRAY) {
        throw DatabindException.from(parser, "modelHttpRetryStatusCodes must be a JSON array");
      }
      List<Integer> codes = new ArrayList<>();
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
          throw DatabindException.from(
              parser, "modelHttpRetryStatusCodes must contain only JSON integers");
        }
        codes.add(parser.getIntValue());
      }
      return new ModelHttpErrorPolicy(codes).retryStatusCodes();
    }
  }
}
