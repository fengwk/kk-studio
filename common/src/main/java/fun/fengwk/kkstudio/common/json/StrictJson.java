package fun.fengwk.kkstudio.common.json;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 严格 JSON 数据绑定：领域 record 就是唯一的数据形态，wire 差异只由 mapper 配置与 mixin 表达，不再为每个类型手写 codec。
 *
 * <p>读取拒绝未知字段、缺失字段（record 每个组件都必须出现，可空组件以显式 {@code null} 出现）、重复键、尾随内容与任何标量强制转换；小数按 {@code
 * BigDecimal} 读取以保留精度；{@link Instant} 只接受 ISO-8601 文本，不接受数字时间戳。写出按 record 组件顺序并显式写出 {@code
 * null}。所有失败都转换为 {@link IllegalArgumentException}；承载秘密的载荷不应使用本类，因为底层错误消息可能回显字段名与值。
 */
public final class StrictJson {

  private static final StrictJson STANDARD = create(builder -> {});

  private final JsonMapper mapper;

  private StrictJson(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /** 不带 mixin 的共享实例，供 wire 形态与领域 record 完全一致的类型使用。 */
  public static StrictJson standard() {
    return STANDARD;
  }

  /** 基础严格配置的实例；{@code customizer} 只用于登记 mixin 与少数 wire 专用的（反）序列化器。 */
  public static StrictJson create(Consumer<JsonMapper.Builder> customizer) {
    Objects.requireNonNull(customizer, "customizer");
    JsonMapper.Builder builder =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY)
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .addModule(
                new SimpleModule("StrictJson").addDeserializer(Instant.class, new IsoInstant()));
    customizer.accept(builder);
    return new StrictJson(builder.build());
  }

  /** 读取一个完整 JSON 文档；空文档、语法错误与结构不符都抛 {@link IllegalArgumentException}。 */
  public <T> T read(String json, Class<T> type, String context) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
    if (json.isBlank()) {
      throw new IllegalArgumentException("malformed " + context + " JSON: empty document");
    }
    try {
      return mapper.readValue(json, type);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  /** 读取元素为 {@code elementType} 的 JSON 数组，返回不可变列表。 */
  public <T> List<T> readList(String json, Class<T> elementType, String context) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(elementType, "elementType");
    if (json.isBlank()) {
      throw new IllegalArgumentException("malformed " + context + " JSON: empty document");
    }
    try {
      List<T> values =
          mapper.readValue(
              json, mapper.getTypeFactory().constructCollectionType(List.class, elementType));
      return List.copyOf(values);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  /** 把已解析的节点绑定为领域值，供外层 codec 委派嵌套字段。 */
  public <T> T read(JsonNode node, Class<T> type, String context) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(type, "type");
    try {
      return mapper.treeToValue(node, type);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed " + context + " JSON", error);
    }
  }

  /** 写出紧凑 JSON 文本。 */
  public String write(Object value, String context) {
    Objects.requireNonNull(value, "value");
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode " + context + " JSON", error);
    }
  }

  /** 写出为节点，供外层 codec 嵌入。 */
  public JsonNode tree(Object value) {
    Objects.requireNonNull(value, "value");
    return mapper.valueToTree(value);
  }

  /** {@link Instant} 只接受 {@link Instant#parse} 认可的 ISO-8601 文本。 */
  private static final class IsoInstant extends StdDeserializer<Instant> {

    private IsoInstant() {
      super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return (Instant) context.handleUnexpectedToken(Instant.class, parser);
      }
      try {
        return Instant.parse(parser.getString());
      } catch (DateTimeParseException error) {
        return (Instant)
            context.handleWeirdStringValue(
                Instant.class, parser.getString(), "not an ISO-8601 instant");
      }
    }
  }
}
