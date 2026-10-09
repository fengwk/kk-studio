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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAiRuntimeDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsNetworkDTO;

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
      // Share DTO 的严格 protocolOptionsJson 规则用 Jackson 2 注解声明，platform 的 Jackson 2 解析器仍然生效；
      // 但 Web 的 HTTP mapper 是 Jackson 3，不识别该注解，会把数字/布尔 token 强制转换成 String。
      // 这里用 mixin 把同一约束装回真实 wire 路径，且只作用于该字段，不改变其它契约的默认 coercion。
      builder.addMixIn(AgentModelVariantDTO.class, ProtocolOptionsJsonMixin.class);
      builder.addMixIn(SystemSettingsNetworkDTO.class, NetworkTextMixin.class);
      // 系统设置与 Provider create/update 的 HTTP 重试白名单同样是严格整数列表：数字 token 绝不做 float/字符串
      // 强制转换；Wire 层只保证 JSON 形态，范围/重复/不可变副本统一由 ModelHttpErrorPolicy 决定。
      builder.addMixIn(SystemSettingsAiRuntimeDTO.class, HttpRetryStatusListMixin.class);
      builder.addMixIn(AgentProviderEditablePropertiesDTO.class, HttpRetryStatusListMixin.class);
    };
  }

  /** 只给 {@code protocolOptionsJson} 指定严格 String 反序列化器，其它属性注解继续由 DTO 自身提供。 */
  abstract static class ProtocolOptionsJsonMixin {

    @JsonDeserialize(using = ProtocolOptionsJsonStrictDeserializer.class)
    String protocolOptionsJson;
  }

  /** 将 Share 的 Jackson 2 网络文本约束桥接到 HTTP Jackson 3；不影响其它 DTO 字段。 */
  abstract static class NetworkTextMixin {

    @JsonDeserialize(using = NetworkTextStrictDeserializer.class)
    String proxyUrl;

    @JsonDeserialize(using = NetworkTextStrictDeserializer.class)
    String noProxyHosts;
  }

  /** 网络字段只接受字符串；null 的可空/必填约束继续由领域边界校验。 */
  static final class NetworkTextStrictDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
        throws JacksonException {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw DatabindException.from(parser, "network text field must be a JSON string");
      }
      return parser.getText();
    }
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
