package fun.fengwk.kkstudio.web.generated;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckRequestDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionCancelRequestDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunRequestDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionVersionDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用真实 HTTP Jackson 3 mapper 的 introspection 与 enum 序列化，把显式选定的稳定传输契约渲染成 TypeScript。
 *
 * <p>这里不解析 Java 源码、不读 JSON 样本猜测类型：属性名与顺序来自 {@code findProperties()}，类型由 Jackson {@link JavaType}
 * 映射，nullability/presence 由属性与全局的 {@code @JsonInclude} 决定，枚举 wire 值由 mapper
 * 对每个实际常量序列化得到。清单之外的类型、enum 或注解形态一律快速失败，绝不回退成 {@code any}。
 *
 * <p>{@link ObjectSpec#requiredProperties()} 只记录已由控制器/服务校验保证的必填属性名，不复制字段类型；生成时逐项与
 * 真实可见属性匹配，任何增删都会失败。
 */
final class StableTsContractGenerator {

  /** wire 方向：响应用序列化视图，请求用反序列化视图，两者都用的类型两个方向属性集必须一致。 */
  enum Direction {
    RESPONSE,
    REQUEST,
    BOTH
  }

  /** 生成产物中的一个契约条目。 */
  interface ContractItem {

    /** TypeScript 导出名。 */
    String tsName();
  }

  /** 一个枚举：TS 类型名、复用的 readonly wire 值数组名与 Java 枚举类型。 */
  record EnumSpec(String tsName, String valuesConstName, Class<? extends Enum<?>> javaType)
      implements ContractItem {}

  /** 一个根 DTO：TS 名、Java 类型、wire 方向与端点保证的必填属性名。 */
  record ObjectSpec(
      String tsName, Class<?> javaType, Direction direction, List<String> requiredProperties)
      implements ContractItem {}

  /** 生成产物的固定顺序：插件连接检查枚举、插件连接检查 DTO、Canvas 版本身份与请求、配置同步类别。 */
  static final List<ContractItem> DEFAULT_ITEMS =
      List.of(
          new EnumSpec(
              "PluginConnectionReachability",
              "PLUGIN_CONNECTION_REACHABILITY_VALUES",
              PluginConnectionCheckDTO.Reachability.class),
          new EnumSpec(
              "PluginConnectionAuthentication",
              "PLUGIN_CONNECTION_AUTHENTICATION_VALUES",
              PluginConnectionCheckDTO.Authentication.class),
          new EnumSpec(
              "PluginConnectionBrowserSession",
              "PLUGIN_CONNECTION_BROWSER_SESSION_VALUES",
              PluginConnectionCheckDTO.BrowserSession.class),
          new ObjectSpec(
              "PluginConnectionCheckDTO",
              PluginConnectionCheckDTO.class,
              Direction.RESPONSE,
              List.of(
                  "pluginId",
                  "appliedEpoch",
                  "appliedRevision",
                  "checkedAt",
                  "reachability",
                  "authentication",
                  "browserSession")),
          new ObjectSpec(
              "PluginConnectionCheckRequestDTO",
              PluginConnectionCheckRequestDTO.class,
              Direction.REQUEST,
              List.of("expectedEpoch", "expectedRevision")),
          new ObjectSpec(
              "CanvasFunctionVersionDTO",
              CanvasFunctionVersionDTO.class,
              Direction.BOTH,
              List.of("catalogEpoch", "definitionFingerprint")),
          new ObjectSpec(
              "CanvasFunctionRunRequestDTO",
              CanvasFunctionRunRequestDTO.class,
              Direction.REQUEST,
              List.of("requestId", "expectedVersion")),
          new ObjectSpec(
              "CanvasFunctionCancelRequestDTO",
              CanvasFunctionCancelRequestDTO.class,
              Direction.REQUEST,
              List.of("requestId")),
          new EnumSpec("ConfigSyncKind", "CONFIG_SYNC_KIND_VALUES", ConfigSyncKind.class));

  private static final String HEADER =
      """
      /**
       * 由生产 HTTP Jackson 3 mapper 生成的稳定传输契约；请勿手改。
       *
       * 生成器：web 测试源码 StableTsContractGenerator
       * 重新生成：scripts/dev/verify/generated-contracts/write.sh
       * 漂移门禁：StableTsContractArtifactTest（mvn verify 覆盖）
       */

      """;

  private static final Pattern TYPESCRIPT_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  private final JsonMapper mapper;
  private final List<ContractItem> items;

  StableTsContractGenerator(JsonMapper mapper) {
    this(mapper, DEFAULT_ITEMS);
  }

  StableTsContractGenerator(JsonMapper mapper, List<ContractItem> items) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.items = List.copyOf(items);
  }

  /** 渲染完整生成产物；同一 mapper 与清单必定产生逐字节一致的结果。 */
  String generate() {
    StringBuilder builder = new StringBuilder(HEADER);
    for (ContractItem item : items) {
      if (item instanceof EnumSpec enumSpec) {
        appendEnum(builder, enumSpec);
      } else if (item instanceof ObjectSpec objectSpec) {
        appendObject(builder, objectSpec);
      } else {
        throw new IllegalStateException("unsupported contract item: " + item);
      }
    }
    return builder.toString();
  }

  /** 枚举：TS 联合类型 + 由 mapper 序列化真实常量得到的 readonly wire 值数组。 */
  private void appendEnum(StringBuilder builder, EnumSpec spec) {
    List<String> wireValues = wireValues(spec);
    builder
        .append("/** Java：")
        .append(javaName(spec.javaType()))
        .append("（mapper 序列化每个常量得到的 wire 值） */")
        .append('\n');
    if (wireValues.size() == 1) {
      builder
          .append("export type ")
          .append(spec.tsName())
          .append(" = ")
          .append(quote(wireValues.get(0)))
          .append('\n');
    } else {
      builder.append("export type ").append(spec.tsName()).append(" =\n");
      for (int i = 0; i < wireValues.size(); i++) {
        builder.append("  | ").append(quote(wireValues.get(i))).append('\n');
      }
    }
    builder.append('\n');
    builder.append("/** Java 声明顺序的 wire 值，供严格解码器复用。 */").append('\n');
    builder
        .append("export const ")
        .append(spec.valuesConstName())
        .append(": readonly ")
        .append(spec.tsName())
        .append("[] = [\n");
    for (String value : wireValues) {
      builder.append("  ").append(quote(value)).append(",\n");
    }
    builder.append("]\n\n");
  }

  /** 逐个序列化枚举的真实常量，任何非 JSON 字符串结果都立即失败。 */
  private List<String> wireValues(EnumSpec spec) {
    Enum<?>[] constants = spec.javaType().getEnumConstants();
    if (constants == null || constants.length == 0) {
      throw new IllegalStateException("enum has no constants: " + spec.tsName());
    }
    List<String> values = new ArrayList<>(constants.length);
    for (Enum<?> constant : constants) {
      String json = mapper.writeValueAsString(constant);
      if (json.length() < 2 || json.charAt(0) != '"' || json.charAt(json.length() - 1) != '"') {
        throw new IllegalStateException(
            spec.tsName()
                + "."
                + constant.name()
                + " must serialize to a JSON string, was "
                + json);
      }
      String value = mapper.readValue(json, String.class);
      if (values.contains(value)) {
        throw new IllegalStateException("duplicate wire value " + value + " in " + spec.tsName());
      }
      values.add(value);
    }
    return values;
  }

  /** 对象：属性名/顺序取自 Jackson introspection，类型与 presence 由真实 Jackson 语义决定。 */
  private void appendObject(StringBuilder builder, ObjectSpec spec) {
    List<TsProperty> properties = properties(spec);
    builder.append("/** Java：").append(javaName(spec.javaType())).append(" */").append('\n');
    builder.append("export interface ").append(spec.tsName()).append(" {\n");
    for (TsProperty property : properties) {
      builder
          .append("  ")
          .append(propertyName(property.name()))
          .append(property.required() ? "" : "?")
          .append(": ")
          .append(property.tsType())
          .append(property.nullable() ? " | null" : "")
          .append('\n');
    }
    builder.append("}\n\n");
  }

  private List<TsProperty> properties(ObjectSpec spec) {
    BeanDescription bean = introspect(spec.javaType(), spec.direction());
    Set<String> required = new LinkedHashSet<>(spec.requiredProperties());
    Set<String> visible = new LinkedHashSet<>();
    List<TsProperty> properties = new ArrayList<>();
    for (BeanPropertyDefinition property : bean.findProperties()) {
      if (!visibleInDirection(spec.direction(), property)) {
        continue;
      }
      String name = property.getName();
      if (!visible.add(name)) {
        throw new IllegalStateException(
            "duplicate visible property " + name + " on " + spec.tsName());
      }
      JavaType javaType = property.getPrimaryType();
      JsonInclude.Include inclusion = inclusion(property, spec.javaType());
      boolean primitive = javaType.isPrimitive();
      boolean explicitlyRequired = required.contains(name);
      boolean isRequired =
          explicitlyRequired || primitive || inclusion == JsonInclude.Include.ALWAYS;
      boolean nullable = inclusion == JsonInclude.Include.ALWAYS && !primitive;
      properties.add(new TsProperty(name, resolveTsType(javaType), isRequired, nullable));
    }
    if (spec.direction() == Direction.BOTH) {
      Set<String> reverse = visiblePropertyNames(spec.javaType(), Direction.REQUEST);
      if (!reverse.equals(visible)) {
        throw new IllegalStateException(
            spec.tsName()
                + " serialization properties "
                + visible
                + " differ from deserialization properties "
                + reverse);
      }
    }
    Set<String> unknownRequired = new LinkedHashSet<>(required);
    unknownRequired.removeAll(visible);
    if (!unknownRequired.isEmpty()) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares required properties "
              + unknownRequired
              + " that are not real wire properties "
              + visible);
    }
    return properties;
  }

  private BeanDescription introspect(Class<?> javaType, Direction direction) {
    JavaType type = mapper.constructType(javaType);
    ClassIntrospector introspector =
        (direction == Direction.REQUEST
                ? mapper.deserializationConfig()
                : mapper.serializationConfig())
            .classIntrospectorInstance();
    var classDef = introspector.introspectClassAnnotations(type);
    return direction == Direction.REQUEST
        ? introspector.introspectForDeserialization(type, classDef)
        : introspector.introspectForSerialization(type, classDef);
  }

  private Set<String> visiblePropertyNames(Class<?> javaType, Direction direction) {
    Set<String> names = new LinkedHashSet<>();
    for (BeanPropertyDefinition property : introspect(javaType, direction).findProperties()) {
      if (visibleInDirection(direction, property) && !names.add(property.getName())) {
        throw new IllegalStateException("duplicate visible property " + property.getName());
      }
    }
    return names;
  }

  private static boolean visibleInDirection(Direction direction, BeanPropertyDefinition property) {
    return direction == Direction.REQUEST ? property.couldDeserialize() : property.couldSerialize();
  }

  /** 属性级 {@code @JsonInclude} 优先，否则落到 mapper 的全局默认（本仓库为 NON_NULL）。 */
  private JsonInclude.Include inclusion(BeanPropertyDefinition property, Class<?> beanClass) {
    JsonInclude.Include fromProperty = explicitInclusion(property);
    if (fromProperty != null && fromProperty != JsonInclude.Include.USE_DEFAULTS) {
      return fromProperty;
    }
    JsonInclude.Include fromConfig =
        mapper.serializationConfig().getDefaultPropertyInclusion(beanClass).getValueInclusion();
    return fromConfig == null || fromConfig == JsonInclude.Include.USE_DEFAULTS
        ? JsonInclude.Include.ALWAYS
        : fromConfig;
  }

  /**
   * 直接从属性成员读取 {@code @JsonInclude}：不经过 {@code findInclusion()}，因为它在反序列化视图或缺 accessor 的属性上 会以 null
   * 成员做注解查找而抛 NPE。
   */
  private static JsonInclude.Include explicitInclusion(BeanPropertyDefinition property) {
    AnnotatedMember[] members = {
      property.getPrimaryMember(),
      property.getGetter(),
      property.getSetter(),
      property.getField(),
      property.getConstructorParameter()
    };
    for (AnnotatedMember member : members) {
      if (member == null) {
        continue;
      }
      JsonInclude annotation = member.getAnnotation(JsonInclude.class);
      if (annotation != null && annotation.value() != JsonInclude.Include.USE_DEFAULTS) {
        return annotation.value();
      }
    }
    return null;
  }

  /** 只实现本范围真正需要的类型分支；其它类型固定失败，绝不兜底成 any。 */
  private String resolveTsType(JavaType type) {
    Class<?> raw = type.getRawClass();
    String registered = registeredName(raw);
    if (registered != null) {
      return registered;
    }
    if (type.isEnumType()) {
      throw new IllegalArgumentException(
          "enum is not part of the generated contract: " + javaName(raw));
    }
    if (raw == String.class || raw == CharSequence.class) {
      return "string";
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return "boolean";
    }
    if (raw == long.class || raw == Long.class) {
      // StrictJacksonConfiguration 把 Java long/Long 固定序列化为十进制字符串。
      return "string";
    }
    if (raw == int.class
        || raw == Integer.class
        || raw == short.class
        || raw == Short.class
        || raw == byte.class
        || raw == Byte.class) {
      return "number";
    }
    if (raw == char.class || raw == Character.class) {
      return "string";
    }
    if (raw == double.class
        || raw == Double.class
        || raw == float.class
        || raw == Float.class
        || raw == BigDecimal.class) {
      return "number";
    }
    if (raw == Instant.class) {
      // WRITE_DATES_AS_TIMESTAMPS：响应 wire 是 epoch 秒 number（可带亚秒小数）。
      return "number";
    }
    if (type.isCollectionLikeType()) {
      return resolveTsType(type.getContentType()) + "[]";
    }
    if (type.isMapLikeType()) {
      JavaType keyType = type.getKeyType();
      if (keyType == null || keyType.getRawClass() != String.class) {
        throw new IllegalArgumentException("only string-keyed maps are supported: " + type);
      }
      return "Record<string, " + resolveTsType(type.getContentType()) + ">";
    }
    throw new IllegalArgumentException("unsupported wire type: " + type);
  }

  private String registeredName(Class<?> raw) {
    for (ContractItem item : items) {
      Class<?> itemType =
          item instanceof EnumSpec enumSpec ? enumSpec.javaType() : ((ObjectSpec) item).javaType();
      if (itemType.equals(raw)) {
        return item.tsName();
      }
    }
    return null;
  }

  private static String propertyName(String name) {
    return TYPESCRIPT_IDENTIFIER.matcher(name).matches() ? name : quote(name);
  }

  private static String quote(String value) {
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private static String javaName(Class<?> type) {
    return type.getName().replace('$', '.');
  }

  /** 单个属性在 wire 上的 TS 投影。 */
  record TsProperty(String name, String tsType, boolean required, boolean nullable) {}
}
