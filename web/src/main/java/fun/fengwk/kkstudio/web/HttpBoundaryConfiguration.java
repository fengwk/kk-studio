package fun.fengwk.kkstudio.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;

import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.UUID;

/** HTTP 输入使用独立的严格读取配置，不改变持久化、插件和集群客户端使用的 mapper。 */
@Configuration(proxyBeanMethods = false)
public class HttpBoundaryConfiguration implements WebMvcConfigurer {

  private static final Converter<String, UUID> UUID_CONVERTER =
      value -> Uuids.parseCanonical(value, "UUID");
  private static final Converter<String, Long> LONG_CONVERTER =
      value -> DecimalLongs.parseNonNegative(value, "long");

  /** Daemon 操作系统族的 HTTP 词汇是小写 wire 值，不是枚举常量名。 */
  private static final Converter<String, DaemonOperatingSystem> OPERATING_SYSTEM_CONVERTER =
      value -> {
        try {
          return DaemonOperatingSystem.fromWireValue(value);
        } catch (IllegalArgumentException error) {
          throw new IllegalArgumentException(
              "operatingSystem must be windows, wsl, linux or macos");
        }
      };

  private final JsonMapper mapper;

  public HttpBoundaryConfiguration(JsonMapper mapper) {
    this.mapper = mapper;
  }

  static JsonMapper strictHttpMapper(JsonMapper mapper) {
    return mapper
        .rebuild()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfigDefaults(
            c -> c.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
        .withCoercionConfig(
            LogicalType.Integer,
            c ->
                c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
        .withCoercionConfig(
            LogicalType.Float,
            c ->
                c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
        .withCoercionConfig(
            LogicalType.Boolean,
            c ->
                c.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
        .withCoercionConfig(
            LogicalType.Textual,
            c ->
                c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .addModule(
            new SimpleModule("CanonicalHttpUuid")
                .addDeserializer(UUID.class, new CanonicalUuidDeserializer()))
        .addModule(
            new SimpleModule("CanonicalHttpLong")
                .addDeserializer(Long.class, new CanonicalLongDeserializer())
                .addDeserializer(Long.TYPE, new CanonicalLongDeserializer()))
        .build();
  }

  static class CanonicalUuidDeserializer extends ValueDeserializer<UUID> {
    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw DatabindException.from(parser, "UUID must be a canonical lowercase string");
      }
      try {
        return Uuids.parseCanonical(parser.getString(), "UUID");
      } catch (IllegalArgumentException error) {
        throw DatabindException.from(parser, "UUID must be a canonical lowercase string");
      }
    }
  }

  static class CanonicalLongDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw DatabindException.from(
            parser, "long must be a canonical non-negative decimal string");
      }
      try {
        return DecimalLongs.parseNonNegative(parser.getString(), "long");
      } catch (IllegalArgumentException error) {
        throw DatabindException.from(
            parser, "long must be a canonical non-negative decimal string");
      }
    }
  }

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (int i = 0; i < converters.size(); i++) {
      if (converters.get(i) instanceof JacksonJsonHttpMessageConverter) {
        converters.set(i, new JacksonJsonHttpMessageConverter(strictHttpMapper(mapper)));
      }
    }
  }

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, UUID.class, UUID_CONVERTER);
    registry.addConverter(String.class, Long.class, LONG_CONVERTER);
    registry.addConverter(String.class, DaemonOperatingSystem.class, OPERATING_SYSTEM_CONVERTER);
  }

  /** 防止 Spring 在转换器拒绝后回退到宽松的默认属性编辑器。 */
  @ControllerAdvice
  public static class CanonicalUuidBinding {
    @InitBinder
    public void bind(WebDataBinder binder) {
      binder.registerCustomEditor(
          UUID.class,
          new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
              setValue(UUID_CONVERTER.convert(text));
            }
          });
      binder.registerCustomEditor(Long.class, longEditor());
      binder.registerCustomEditor(long.class, longEditor());
    }

    private PropertyEditorSupport longEditor() {
      return new PropertyEditorSupport() {
        @Override
        public void setAsText(String text) {
          setValue(LONG_CONVERTER.convert(text));
        }
      };
    }
  }
}
