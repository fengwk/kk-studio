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
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsAdvancedDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsAiRuntimeDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsEnvironmentDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsStorageMediaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsToolDTO;

import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.UUID;

/** HTTP 输入使用独立的严格读取配置，不改变持久化、插件和集群客户端使用的 mapper。 */
@Configuration(proxyBeanMethods = false)
public class HttpBoundaryConfiguration implements WebMvcConfigurer {

  private static final Converter<String, UUID> UUID_CONVERTER =
      value -> Uuids.parseCanonical(value, "UUID");

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
        // Settings 的 Long wire 本就明确为十进制字符串，而不是 JSON 数字。
        .addMixIn(SystemSettingsToolDTO.class, SettingsToolMixin.class)
        .addMixIn(SystemSettingsAiRuntimeDTO.class, SettingsAiRuntimeMixin.class)
        .addMixIn(SystemSettingsEnvironmentDTO.class, SettingsEnvironmentMixin.class)
        .addMixIn(SystemSettingsStorageMediaDTO.class, SettingsStorageMediaMixin.class)
        .addMixIn(SystemSettingsAdvancedDTO.class, SettingsAdvancedMixin.class)
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

  static class DecimalStringLong extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw DatabindException.from(parser, "settings long must be a decimal string");
      }
      try {
        return DecimalLongs.parseNonNegative(parser.getString(), "settings long");
      } catch (IllegalArgumentException error) {
        throw DatabindException.from(parser, "settings long must be a canonical decimal string");
      }
    }
  }

  abstract static class SettingsToolMixin {
    @JsonDeserialize(using = DecimalStringLong.class)
    Long modelGatewayBusyRetryMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long toolGatewayOverloadRetryMillis;
  }

  abstract static class SettingsAiRuntimeMixin
      extends StrictJacksonConfiguration.HttpRetryStatusListMixin {
    @JsonDeserialize(using = DecimalStringLong.class)
    Long retryBaseDelayMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long retryMaxDelayMillis;
  }

  abstract static class SettingsEnvironmentMixin {
    @JsonDeserialize(using = DecimalStringLong.class)
    Long maxResourceBytes;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long heartbeatTimeoutMillis;
  }

  abstract static class SettingsStorageMediaMixin {
    @JsonDeserialize(using = DecimalStringLong.class)
    Long uploadExpiresSeconds;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long s3PresignDefaultExpiresSeconds;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long s3PresignMaxExpiresSeconds;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long canvasMediaProcessTimeoutMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long temporaryResourceTtlSeconds;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long temporaryResourceCleanupIntervalSeconds;
  }

  abstract static class SettingsAdvancedMixin {
    @JsonDeserialize(using = DecimalStringLong.class)
    Long resourceMaxBytes;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long processorLeaseDurationMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long processorHeartbeatIntervalMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long threadResolveFailureDelayMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long modelDispatchBusyFallbackDelayMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long toolPreflightFailureDelayMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long toolDispatchBusyFallbackDelayMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long applicationEventMaxBytes;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long applicationEventSendTimeoutMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long applicationEventHeartbeatIntervalMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long postgresqlWorkNotificationPollMillis;

    @JsonDeserialize(using = DecimalStringLong.class)
    Long postgresqlWorkReconnectBackoffMillis;
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
  }

  /** 防止 Spring 在转换器拒绝后回退到宽松的默认 UUID 属性编辑器。 */
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
    }
  }
}
