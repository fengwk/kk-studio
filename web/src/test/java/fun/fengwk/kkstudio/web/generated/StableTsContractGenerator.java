package fun.fengwk.kkstudio.web.generated;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.introspect.AnnotatedClass;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
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
 * <p>这里不解析 Java 源码、不读 JSON 样本猜测类型：属性名与顺序来自 {@code findProperties()}，标量 wire 形态由真实 mapper 的序列化能力核验（见
 * {@link #validateWireProfile}），枚举 wire 值由 mapper 对每个实际常量序列化得到。
 * 清单之外的类型、enum、注解形态与标量配置漂移一律快速失败，绝不回退成 {@code any}，也不把未支持的定制当成 JavaType。
 *
 * <p>{@link ObjectSpec#requiredProperties()} 只列出当前范围已由控制器/服务保证必填的属性名，不复制字段类型。生成时逐项与
 * 真实可见属性匹配：清单里出现非真实 wire 属性、或清单里的属性被删除/改名都会失败；反过来新增一个可见属性本身不会失败， 它会改变生成产物并由漂移门禁 {@code
 * StableTsContractArtifactTest} 拦截，要求显式重新生成与审阅。
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

  /**
   * 一个根 DTO：TS 名、Java 类型、wire 方向与端点保证的必填属性名。
   *
   * <p>{@code requiredProperties} 只列当前范围已保证必填的属性名，不复制类型；生成时逐项与真实可见属性核对，非真实属性或
   * 被删除/改名会失败，新增可见属性则由漂移门禁拦截。
   */
  record ObjectSpec(
      String tsName, Class<?> javaType, Direction direction, List<String> requiredProperties)
      implements ContractItem {}

  /** 一个标量探针：Java 类型、已知道的探针值与它对映的 TS 类型；构造时用真实 mapper 核验。 */
  private record ScalarSpec(Class<?> javaType, Object probe, String tsType) {}

  private static final Instant INSTANT_PROBE = Instant.ofEpochSecond(1772366400L);

  /**
   * 预设的标量 wire 形态。生成本身不做序列化采样，但构造时会用这些探针值核验 mapper 的真实序列化能力：配置漂移（例如 去掉 Long→String、把 Instant 改成 ISO
   * 文本或毫秒）会让生成固定失败，而不是继续产出过时的 TS。
   */
  private static final List<ScalarSpec> SCALARS =
      List.of(
          new ScalarSpec(String.class, "1", "string"),
          new ScalarSpec(boolean.class, true, "boolean"),
          new ScalarSpec(Boolean.class, true, "boolean"),
          new ScalarSpec(int.class, 1, "number"),
          new ScalarSpec(Integer.class, 1, "number"),
          new ScalarSpec(short.class, (short) 1, "number"),
          new ScalarSpec(Short.class, (short) 1, "number"),
          new ScalarSpec(byte.class, (byte) 1, "number"),
          new ScalarSpec(Byte.class, (byte) 1, "number"),
          new ScalarSpec(long.class, 1L, "string"),
          new ScalarSpec(Long.class, 1L, "string"),
          new ScalarSpec(double.class, 1.5d, "number"),
          new ScalarSpec(Double.class, 1.5d, "number"),
          new ScalarSpec(float.class, 1.5f, "number"),
          new ScalarSpec(Float.class, 1.5f, "number"),
          new ScalarSpec(BigDecimal.class, new BigDecimal("1.5"), "number"),
          new ScalarSpec(char.class, 'a', "string"),
          new ScalarSpec(Character.class, 'a', "string"),
          new ScalarSpec(Instant.class, INSTANT_PROBE, "number"));

  /** 生成器不支持的属性级定制：出现即固定失败，而不是静默按 JavaType 当 wire 结果。 */
  private static final List<String> JSON_SERIALIZE_MEMBERS =
      List.of(
          "using",
          "contentUsing",
          "keyUsing",
          "nullsUsing",
          "as",
          "contentAs",
          "keyAs",
          "converter",
          "contentConverter",
          "typing");

  /** {@code @JsonFormat} 的形态/日期时间定制当前不支持；任意成员非默认即失败。 */
  private static final List<String> JSON_FORMAT_MEMBERS =
      List.of("shape", "pattern", "locale", "timezone", "lenient", "with", "without");

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
                  "appliedRevision",
                  "checkedAt",
                  "reachability",
                  "authentication",
                  "browserSession")),
          new ObjectSpec(
              "PluginConnectionCheckRequestDTO",
              PluginConnectionCheckRequestDTO.class,
              Direction.REQUEST,
              List.of("expectedRevision")),
          new ObjectSpec(
              "CanvasFunctionVersionDTO",
              CanvasFunctionVersionDTO.class,
              Direction.BOTH,
              List.of("definitionFingerprint")),
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
    validateWireProfile(mapper);
  }

  /**
   * 用真实 mapper 序列化标量探针，核验它的 wire 形态仍等于生成器预设的 TS 类型。
   *
   * <p>这是核 wire profile，不是凭样本猜 Java 类型：探针值是已知道的常量，只用来证明 mapper 的配置仍把 long/Long 写成 字符串、把 Instant 写成
   * epoch 秒 number 等。任一失配（含 Instant 变成毫秒）都固定失败，避免在配置漂移后继续生成 过时的 TS。
   */
  private static void validateWireProfile(JsonMapper mapper) {
    List<String> mismatches = new ArrayList<>();
    for (ScalarSpec spec : SCALARS) {
      JsonNode node = mapper.readTree(mapper.writeValueAsString(spec.probe()));
      String actual = wireShape(node);
      if (!spec.tsType().equals(actual)) {
        mismatches.add(javaName(spec.javaType()) + " -> " + actual + ", expected " + spec.tsType());
      }
    }
    JsonNode instant = mapper.readTree(mapper.writeValueAsString(INSTANT_PROBE));
    if (!instant.isNumber() || instant.asDouble() != 1772366400d) {
      mismatches.add(
          javaName(Instant.class) + " -> " + instant + ", expected epoch seconds number");
    }
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "HTTP mapper wire profile drifted from the generated TS mapping; update the generator"
              + " deliberately: "
              + mismatches);
    }
  }

  private static String wireShape(JsonNode node) {
    if (node.isString()) {
      return "string";
    }
    if (node.isNumber()) {
      return "number";
    }
    if (node.isBoolean()) {
      return "boolean";
    }
    return "non-scalar " + node.getNodeType();
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
    // 每个分块自身以空行收尾，这里去掉末项的尾随空行，产物恰好以一个换行结束。
    return builder.toString().stripTrailing() + '\n';
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

  /**
   * 对象：属性名/顺序取自 Jackson introspection；presence 与 nullable 按方向取真实语义。
   *
   * <p>响应方向（含 BOTH）由 {@code @JsonInclude} 决定：ALWAYS 的属性在 wire 上必然出现且可为 null，NON_NULL 的可选性由
   * 必填清单表达。请求方向只看反序列化视图：presence 来自必填清单与原始类型，{@code @JsonInclude} 只作用于序列化，不参与 推断，也不因此产生 null。仅支持
   * ALWAYS/NON_NULL，其它形态与属性级定制固定失败。
   */
  private List<TsProperty> properties(ObjectSpec spec) {
    BeanDescription bean = introspect(spec.javaType(), spec.direction());
    requireSupportedRootShape(spec, bean);
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
      requireSupportedProperty(spec, property);
      JavaType javaType = property.getPrimaryType();
      boolean primitive = javaType.isPrimitive();
      boolean isRequired;
      boolean nullable;
      if (spec.direction() == Direction.REQUEST) {
        isRequired = required.contains(name) || primitive;
        nullable = false;
      } else {
        JsonInclude.Include inclusion = inclusion(bean, property);
        isRequired =
            required.contains(name) || primitive || inclusion == JsonInclude.Include.ALWAYS;
        nullable = inclusion == JsonInclude.Include.ALWAYS && !primitive;
      }
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

  /**
   * 根类型只支持字段直出的 DTO：class 级定制序列化/格式化、类型信息、动态属性、@JsonValue/@JsonUnwrapped 都会改变真实 wire
   * 形态，因此固定失败；默认注解形态（例如 {@code @JsonFormat(shape = ANY)}）不受影响。
   */
  private void requireSupportedRootShape(ObjectSpec spec, BeanDescription bean) {
    AnnotatedClass classInfo = bean.getClassInfo();
    if (classInfo.getAnnotation(JsonTypeInfo.class) != null) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares @JsonTypeInfo, which is not supported by the generated contract");
    }
    JsonSerialize classSerializer = classInfo.getAnnotation(JsonSerialize.class);
    if (classSerializer != null && !isDefaultAnnotation(classSerializer, JSON_SERIALIZE_MEMBERS)) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares a class-level @JsonSerialize customization, which is not supported by the"
              + " generated contract");
    }
    JsonFormat classFormat = classInfo.getAnnotation(JsonFormat.class);
    if (classFormat != null && !isDefaultAnnotation(classFormat, JSON_FORMAT_MEMBERS)) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares a class-level @JsonFormat shape/date customization, which is not supported"
              + " by the generated contract");
    }
    if (bean.findJsonValueAccessor() != null) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares @JsonValue, which is not supported for a generated object contract");
    }
    if (bean.findAnyGetter() != null) {
      throw new IllegalStateException(
          spec.tsName()
              + " declares @JsonAnyGetter, whose dynamic properties are not supported by the generated"
              + " contract");
    }
    for (BeanPropertyDefinition property : bean.findProperties()) {
      for (AnnotatedMember member : members(property)) {
        if (member.getAnnotation(JsonUnwrapped.class) != null) {
          throw new IllegalStateException(
              spec.tsName()
                  + "."
                  + property.getName()
                  + " declares @JsonUnwrapped, which is not supported by the generated contract");
        }
      }
    }
  }

  /** 属性级定制序列化/格式化会改变真实 wire 形态，当前不支持，必须失败而不是当成 JavaType。 */
  private void requireSupportedProperty(ObjectSpec spec, BeanPropertyDefinition property) {
    for (AnnotatedMember member : members(property)) {
      JsonSerialize serializer = member.getAnnotation(JsonSerialize.class);
      if (serializer != null && !isDefaultAnnotation(serializer, JSON_SERIALIZE_MEMBERS)) {
        throw new IllegalStateException(
            spec.tsName()
                + "."
                + property.getName()
                + " declares a custom @JsonSerialize, which is not supported by the generated"
                + " contract");
      }
      JsonFormat format = member.getAnnotation(JsonFormat.class);
      if (format != null && !isDefaultAnnotation(format, JSON_FORMAT_MEMBERS)) {
        throw new IllegalStateException(
            spec.tsName()
                + "."
                + property.getName()
                + " declares a @JsonFormat shape/date customization, which is not supported by the"
                + " generated contract");
      }
    }
  }

  /** 注解成员是否全部保持声明默认值；用于对未支持的定制形态 fail-closed。 */
  private static boolean isDefaultAnnotation(Annotation annotation, List<String> members) {
    for (String member : members) {
      try {
        Method accessor = annotation.annotationType().getDeclaredMethod(member);
        if (!Objects.deepEquals(accessor.getDefaultValue(), accessor.invoke(annotation))) {
          return false;
        }
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException(
            "cannot inspect @" + annotation.annotationType().getSimpleName() + "." + member, ex);
      }
    }
    return true;
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

  /**
   * 序列化方向的 presence 规则：属性级 {@code @JsonInclude} 优先，其次 class 级，最后落到 mapper 的全局默认（本仓库为
   * NON_NULL，配置未声明时等价于 ALWAYS）。只支持 ALWAYS 与 NON_NULL；NON_EMPTY/NON_DEFAULT/NON_ABSENT/CUSTOM 等当前
   * 不支持，固定失败而不是被当成 NON_NULL。
   */
  private JsonInclude.Include inclusion(BeanDescription bean, BeanPropertyDefinition property) {
    JsonInclude.Include resolved = explicitInclusion(property);
    if (resolved == null) {
      JsonInclude annotation = bean.getClassInfo().getAnnotation(JsonInclude.class);
      if (annotation != null && annotation.value() != JsonInclude.Include.USE_DEFAULTS) {
        resolved = annotation.value();
      }
    }
    if (resolved == null) {
      JsonInclude.Include fromConfig =
          mapper
              .serializationConfig()
              .getDefaultPropertyInclusion(bean.getBeanClass())
              .getValueInclusion();
      resolved =
          fromConfig == null || fromConfig == JsonInclude.Include.USE_DEFAULTS
              ? JsonInclude.Include.ALWAYS
              : fromConfig;
    }
    if (resolved != JsonInclude.Include.ALWAYS && resolved != JsonInclude.Include.NON_NULL) {
      throw new IllegalStateException(
          "unsupported @JsonInclude "
              + resolved
              + " on "
              + property.getName()
              + "; only ALWAYS and NON_NULL are supported by the generated contract");
    }
    return resolved;
  }

  /**
   * 直接从属性成员读取 {@code @JsonInclude}：不经过 {@code findInclusion()}，因为它在反序列化视图或缺 accessor 的属性上 会以 null
   * 成员做注解查找而抛 NPE。
   */
  private static JsonInclude.Include explicitInclusion(BeanPropertyDefinition property) {
    for (AnnotatedMember member : members(property)) {
      JsonInclude annotation = member.getAnnotation(JsonInclude.class);
      if (annotation != null && annotation.value() != JsonInclude.Include.USE_DEFAULTS) {
        return annotation.value();
      }
    }
    return null;
  }

  /** 一个属性的全部候选成员：primary/accessor/field/构造参数；缺失的成员被跳过。 */
  private static List<AnnotatedMember> members(BeanPropertyDefinition property) {
    AnnotatedMember[] candidates = {
      property.getPrimaryMember(),
      property.getGetter(),
      property.getSetter(),
      property.getField(),
      property.getConstructorParameter()
    };
    List<AnnotatedMember> present = new ArrayList<>(candidates.length);
    for (AnnotatedMember member : candidates) {
      if (member != null) {
        present.add(member);
      }
    }
    return present;
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
    String scalar = scalarTsType(raw);
    if (scalar != null) {
      // 标量映射与真实 mapper 的序列化能力已在构造时核验（见 validateWireProfile）。
      return scalar;
    }
    if (type.isCollectionLikeType()) {
      return resolveTsType(type.getContentType()) + "[]";
    }
    if (type.isMapLikeType()) {
      JavaType keyType = type.getKeyType();
      if (keyType == null || !"string".equals(scalarTsType(keyType.getRawClass()))) {
        throw new IllegalArgumentException("only string-keyed maps are supported: " + type);
      }
      return "Record<string, " + resolveTsType(type.getContentType()) + ">";
    }
    throw new IllegalArgumentException("unsupported wire type: " + type);
  }

  /** 标量的 TS 类型；非标量返回 null。映射表本身由 {@link #validateWireProfile} 用真实 mapper 核验。 */
  private static String scalarTsType(Class<?> raw) {
    for (ScalarSpec spec : SCALARS) {
      if (spec.javaType() == raw) {
        return spec.tsType();
      }
    }
    return null;
  }

  private String registeredName(Class<?> raw) {
    for (ContractItem item : items) {
      Class<?> itemType;
      if (item instanceof EnumSpec enumSpec) {
        itemType = enumSpec.javaType();
      } else if (item instanceof ObjectSpec objectSpec) {
        itemType = objectSpec.javaType();
      } else {
        throw new IllegalStateException("unsupported contract item: " + item);
      }
      if (itemType.equals(raw)) {
        return item.tsName();
      }
    }
    return null;
  }

  private static String propertyName(String name) {
    return TYPESCRIPT_IDENTIFIER.matcher(name).matches() ? name : quote(name);
  }

  /** 生成单引号 TS 字符串字面量；控制字符与行终止符无法安全内联，固定失败而不是产出非法 TS。 */
  private static String quote(String value) {
    StringBuilder quoted = new StringBuilder(value.length() + 2).append('\'');
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (character == '\\' || character == '\'') {
        quoted.append('\\').append(character);
      } else if (character < 0x20
          || character == 0x7F
          || (character >= 0x80 && character <= 0x9F)
          || character == 0x2028
          || character == 0x2029) {
        throw new IllegalStateException(
            String.format(
                "cannot emit a TypeScript string literal containing unsupported character U+%04X"
                    + " (control or line terminator)",
                (int) character));
      } else {
        quoted.append(character);
      }
    }
    return quoted.append('\'').toString();
  }

  private static String javaName(Class<?> type) {
    return type.getName().replace('$', '.');
  }

  /** 单个属性在 wire 上的 TS 投影。 */
  record TsProperty(String name, String tsType, boolean required, boolean nullable) {}
}
