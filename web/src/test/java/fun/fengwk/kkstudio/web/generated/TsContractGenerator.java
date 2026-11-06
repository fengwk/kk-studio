package fun.fengwk.kkstudio.web.generated;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotatedField;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.platform.catalog.dto.DecimalStringSerializer;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRatePatchDTOSerializer;
import fun.fengwk.kkstudio.platform.http.ApiResponse;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Web 控制器签名与生产 HTTP Jackson 3 mapper 生成全部传输契约的 TypeScript 类型。
 *
 * <p>根类型取自控制器处理方法的 {@code @RequestBody}、路径及查询参数（请求方向）与返回值（响应方向），再加上嵌在错误响应上下文里的结构化载荷；
 * 从根出发沿属性闭包收集契约包（生产为 {@code fun.fengwk.kkstudio}）下的 DTO 与枚举。属性名与顺序来自真实 mapper 的 introspection，枚举
 * wire 值由 mapper 序列化每个常量得到，标量映射在构造时用探针核验。presence 规则：
 *
 * <ul>
 *   <li>未标 {@link Nullable} 的属性必填且非空；原始类型同理。
 *   <li>写出方向带 {@code @JsonInclude(ALWAYS)} 的可空属性必定出现：{@code T | null}。
 *   <li>其它可空属性可以缺省，消费方把缺省与 null 同等对待：{@code ?: T | null}。
 * </ul>
 *
 * <p>任何会改变写出形态的定制（类级或属性级序列化器、格式化、unwrapped、any getter、契约包外的对象类型）都固定失败，绝不回退成 {@code
 * any}。例外只有两张显式白名单：只写出 JSON 字符串的属性级序列化器（属性或元素投影为 {@code string}），以及只省略未声明字段的类级序列化器（全部属性投影为可选）。
 * 定制反序列化器只允许收紧输入，必须接受属性声明的形态。
 */
final class TsContractGenerator {

  /** 一个类型在 wire 上被使用的方向。 */
  enum Direction {
    REQUEST,
    RESPONSE,
    BOTH;

    Direction merge(Direction other) {
      return this == other ? this : BOTH;
    }
  }

  /** 标量探针：Java 类型、已知探针值与对映的 TS 类型；构造时用真实 mapper 核验。 */
  private record ScalarSpec(Class<?> javaType, Object probe, String tsType) {}

  private static final Instant INSTANT_PROBE = Instant.ofEpochSecond(1772366400L);

  private static final List<ScalarSpec> SCALARS =
      List.of(
          new ScalarSpec(String.class, "1", "string"),
          new ScalarSpec(boolean.class, true, "boolean"),
          new ScalarSpec(Boolean.class, true, "boolean"),
          new ScalarSpec(int.class, 1, "number"),
          new ScalarSpec(Integer.class, 1, "number"),
          new ScalarSpec(long.class, 1L, "string"),
          new ScalarSpec(Long.class, 1L, "string"),
          new ScalarSpec(double.class, 1.5d, "number"),
          new ScalarSpec(Double.class, 1.5d, "number"),
          new ScalarSpec(BigDecimal.class, new BigDecimal("1.5"), "number"),
          new ScalarSpec(Instant.class, INSTANT_PROBE, "number"),
          new ScalarSpec(UUID.class, new UUID(0L, 1L), "string"));

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

  private static final List<String> JSON_FORMAT_MEMBERS =
      List.of("shape", "pattern", "locale", "timezone", "lenient", "with", "without");

  /** 只写出 JSON 字符串的属性级序列化器：价格 decimal 写作普通十进制字符串。 */
  private static final Set<Class<?>> STRING_SERIALIZERS = Set.of(DecimalStringSerializer.class);

  /** 写出属性与类型不变、只省略未声明字段的类级序列化器：任何属性都可能缺省。 */
  private static final Set<Class<?>> PRESENCE_SERIALIZERS =
      Set.of(ModelRatePatchDTOSerializer.class);

  private static final String HEADER =
      """
      /**
       * 由 Web 控制器签名与生产 HTTP Jackson 3 mapper 生成的全部传输契约；请勿手改。
       *
       * 生成器：web 测试源码 TsContractGenerator
       * 重新生成：scripts/dev/verify/generated-contracts/write.sh
       * 漂移门禁：TsContractArtifactTest（mvn verify 覆盖）
       */

      """;

  private static final Pattern TYPESCRIPT_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

  private final JsonMapper mapper;
  private final List<String> contractPackages;
  private final List<Class<?>> controllers;
  private final List<Class<?>> errorPayloadTypes;
  private final Map<Class<?>, Direction> directions = new HashMap<>();
  private final Deque<Class<?>> pending = new ArrayDeque<>();

  TsContractGenerator(
      JsonMapper mapper,
      List<String> contractPackages,
      List<Class<?>> controllers,
      List<Class<?>> errorPayloadTypes) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.contractPackages = contractPackages.stream().map(name -> name + ".").toList();
    this.controllers = List.copyOf(controllers);
    this.errorPayloadTypes = List.copyOf(errorPayloadTypes);
    validateWireProfile(mapper);
  }

  /** 渲染完整生成产物；同一组控制器与 mapper 必定产生逐字节一致的结果。 */
  String generate() {
    directions.clear();
    pending.clear();
    Map<String, RouteSpec> routes = discoverRoutes();
    for (Class<?> controller : controllers) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        for (Parameter parameter : method.getParameters()) {
          if (parameter.isAnnotationPresent(RequestBody.class)) {
            visit(parameter.getParameterizedType(), Direction.REQUEST);
          }
          if (parameter.isAnnotationPresent(RequestParam.class)
              || parameter.isAnnotationPresent(PathVariable.class)) {
            visit(parameter.getParameterizedType(), Direction.REQUEST);
          }
        }
        visit(unwrapResponseType(method.getGenericReturnType()), Direction.RESPONSE);
      }
    }
    for (Class<?> payload : errorPayloadTypes) {
      visit(payload, Direction.RESPONSE);
    }
    while (!pending.isEmpty()) {
      Class<?> type = pending.removeFirst();
      expand(type, directions.get(type));
    }
    Map<String, Class<?>> ordered = new TreeMap<>();
    for (Class<?> type : directions.keySet()) {
      Class<?> previous = ordered.put(tsName(type), type);
      if (previous != null) {
        throw new IllegalStateException(
            "TypeScript name collision " + tsName(type) + ": " + previous + " and " + type);
      }
    }
    StringBuilder builder = new StringBuilder(HEADER);
    for (Class<?> type : ordered.values()) {
      if (type == Page.class) {
        appendPageInterface(builder);
      } else if (type.isEnum()) {
        appendEnum(builder, type);
      } else if (isPolymorphic(type)) {
        appendUnion(builder, type);
      } else if (!isSubtypeOfPolymorphic(type)) {
        appendObject(builder, type, directions.get(type), null);
      }
    }
    appendApiRoutes(builder, routes);
    return builder.toString().stripTrailing() + '\n';
  }

  private void appendPageInterface(StringBuilder builder) {
    builder.append("/** Java：").append(javaName(Page.class)).append(" */\n");
    builder.append("export interface Page<T> {\n");
    for (TsProperty property : properties(Page.class, Direction.RESPONSE)) {
      try {
        Type declared = Page.class.getDeclaredField(property.name()).getGenericType();
        builder
            .append("  ")
            .append(propertyName(property.name()))
            .append(property.optional() ? "?" : "")
            .append(": ")
            .append(tsType(declared, Direction.RESPONSE))
            .append(property.nullable() ? " | null" : "")
            .append('\n');
      } catch (NoSuchFieldException ex) {
        throw new IllegalStateException("cannot inspect Page property " + property.name(), ex);
      }
    }
    builder.append("}\n\n");
  }

  private void appendApiRoutes(StringBuilder builder, Map<String, RouteSpec> routes) {
    builder.append("export interface ApiRoutes {\n");
    for (Map.Entry<String, RouteSpec> entry : routes.entrySet()) {
      RouteSpec route = entry.getValue();
      builder.append("  ").append(quote(entry.getKey())).append(": {\n");
      if (route.hasPath()) {
        builder.append("    path: ").append(route.renderPath()).append('\n');
      }
      if (route.hasQuery()) {
        builder.append("    query: ").append(route.renderQuery()).append('\n');
      }
      if (route.hasBody()) {
        builder
            .append("    body")
            .append(route.body().optional() ? "?" : "")
            .append(": ")
            .append(route.body().tsType())
            .append('\n');
      }
      builder.append("    response: ").append(route.responseType()).append('\n');
      if (route.hasResponseType()) {
        builder.append("    responseType: ").append(quote(route.responseTypeKind())).append('\n');
      }
      builder.append("  }\n");
    }
    builder.append("}\n\n");
    builder.append(
        "export const API_RESPONSE_TYPES: Partial<Record<keyof ApiRoutes, 'text' | 'blob'>> = {\n");
    for (Map.Entry<String, RouteSpec> entry : routes.entrySet()) {
      if (entry.getValue().hasResponseType()) {
        builder
            .append("  ")
            .append(quote(entry.getKey()))
            .append(": ")
            .append(quote(entry.getValue().responseTypeKind()))
            .append(",\n");
      }
    }
    builder.append("}\n");
  }

  private void visit(Type type, Direction direction) {
    if (type instanceof Class<?> raw) {
      if (raw.isArray()) {
        visit(raw.getComponentType(), direction);
        return;
      }
      if (isContractType(raw)) {
        register(raw, direction);
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      if (parameterized.getRawType() instanceof Class<?> raw) {
        if (raw != ApiResponse.class && raw != ResponseEntity.class && isContractType(raw)) {
          register(raw, direction);
        }
      }
      for (Type argument : parameterized.getActualTypeArguments()) {
        visit(argument, direction);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      visit(array.getGenericComponentType(), direction);
    }
  }

  private boolean isContractType(Class<?> type) {
    return contractPackages.stream().anyMatch(prefix -> type.getName().startsWith(prefix));
  }

  private void register(Class<?> type, Direction direction) {
    Direction previous = directions.get(type);
    Direction merged = previous == null ? direction : previous.merge(direction);
    if (merged != previous) {
      directions.put(type, merged);
      pending.addLast(type);
    }
  }

  private void expand(Class<?> type, Direction direction) {
    if (type.isEnum() || type == Page.class) {
      return;
    }
    if (isPolymorphic(type)) {
      for (JsonSubTypes.Type subtype : subtypes(type)) {
        register(subtype.value(), direction);
      }
      return;
    }
    for (Direction view : views(direction)) {
      for (BeanPropertyDefinition property : introspect(type, view).findProperties()) {
        if (visibleInDirection(view, property)) {
          visitJavaType(declaredType(property), direction);
        }
      }
    }
  }

  private void visitJavaType(JavaType type, Direction direction) {
    Class<?> raw = type.getRawClass();
    if (isContractType(raw)) {
      register(raw, direction);
    }
    for (int i = 0; i < type.containedTypeCount(); i++) {
      visitJavaType(type.containedType(i), direction);
    }
    if (type.getContentType() != null) {
      visitJavaType(type.getContentType(), direction);
    }
  }

  private static List<Direction> views(Direction direction) {
    return direction == Direction.BOTH
        ? List.of(Direction.RESPONSE, Direction.REQUEST)
        : List.of(direction);
  }

  /** 用真实 mapper 序列化标量探针，核验 wire 形态仍等于预设的 TS 映射；配置漂移即失败。 */
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
      mismatches.add("Instant -> " + instant + ", expected epoch seconds number");
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

  /** 枚举：TS 联合类型 + 由 mapper 序列化真实常量得到的 readonly wire 值数组；@JsonValue 提供 wire 词汇。 */
  private void appendEnum(StringBuilder builder, Class<?> type) {
    List<String> wireValues = wireValues(type);
    String name = tsName(type);
    builder.append("/** Java：").append(javaName(type)).append(" */\n");
    builder.append("export type ").append(name).append(" =");
    if (wireValues.size() == 1) {
      builder.append(' ').append(quote(wireValues.get(0))).append('\n');
    } else {
      builder.append('\n');
      for (String value : wireValues) {
        builder.append("  | ").append(quote(value)).append('\n');
      }
    }
    builder.append('\n');
    builder
        .append("export const ")
        .append(constantName(name))
        .append("_VALUES: readonly ")
        .append(name)
        .append("[] = [\n");
    for (String value : wireValues) {
      builder.append("  ").append(quote(value)).append(",\n");
    }
    builder.append("]\n\n");
  }

  private List<String> wireValues(Class<?> type) {
    Object[] constants = type.getEnumConstants();
    if (constants == null || constants.length == 0) {
      throw new IllegalStateException("enum has no constants: " + javaName(type));
    }
    List<String> values = new ArrayList<>(constants.length);
    Method jsonValue =
        Arrays.stream(type.getMethods())
            .filter(method -> method.isAnnotationPresent(JsonValue.class))
            .findFirst()
            .orElse(null);
    for (Object constant : constants) {
      Object wire = constant;
      if (jsonValue != null) {
        try {
          wire = jsonValue.invoke(constant);
        } catch (ReflectiveOperationException error) {
          throw new IllegalStateException(javaName(type) + " @JsonValue must be callable", error);
        }
      }
      JsonNode node = mapper.readTree(mapper.writeValueAsString(wire));
      if (!node.isString()) {
        throw new IllegalStateException(
            javaName(type) + "." + constant + " must serialize to a JSON string, was " + node);
      }
      if (values.contains(node.stringValue())) {
        throw new IllegalStateException(
            "duplicate wire value " + node.stringValue() + " in " + javaName(type));
      }
      values.add(node.stringValue());
    }
    return values;
  }

  /** sealed 多态：每个子类型一个带判别字面量的接口，根类型是它们的联合。 */
  private void appendUnion(StringBuilder builder, Class<?> type) {
    JsonTypeInfo info = type.getAnnotation(JsonTypeInfo.class);
    if (info.use() != JsonTypeInfo.Id.NAME
        || info.include() != JsonTypeInfo.As.PROPERTY
        || info.property().isEmpty()) {
      throw new IllegalStateException(
          javaName(type)
              + " must use @JsonTypeInfo(use = NAME, include = PROPERTY, property = ...)");
    }
    List<JsonSubTypes.Type> subtypes = subtypes(type);
    for (JsonSubTypes.Type subtype : subtypes) {
      appendObject(
          builder,
          subtype.value(),
          directions.get(subtype.value()),
          new Discriminator(info.property(), subtype.name()));
    }
    builder.append("/** Java：").append(javaName(type)).append(" */\n");
    builder.append("export type ").append(tsName(type)).append(" =\n");
    for (JsonSubTypes.Type subtype : subtypes) {
      builder.append("  | ").append(tsName(subtype.value())).append('\n');
    }
    builder.append('\n');
  }

  private record Discriminator(String property, String value) {}

  private void appendObject(
      StringBuilder builder,
      Class<?> type,
      Direction direction,
      @Nullable Discriminator discriminator) {
    List<TsProperty> properties = properties(type, direction);
    builder.append("/** Java：").append(javaName(type)).append(" */\n");
    builder.append("export interface ").append(tsName(type)).append(" {\n");
    if (discriminator != null) {
      if (properties.stream().anyMatch(p -> p.name().equals(discriminator.property()))) {
        throw new IllegalStateException(
            javaName(type) + " must not declare its type property " + discriminator.property());
      }
      builder
          .append("  ")
          .append(propertyName(discriminator.property()))
          .append(": ")
          .append(quote(discriminator.value()))
          .append('\n');
    }
    for (TsProperty property : properties) {
      builder
          .append("  ")
          .append(propertyName(property.name()))
          .append(property.optional() ? "?" : "")
          .append(": ")
          .append(property.tsType())
          .append(property.nullable() ? " | null" : "")
          .append('\n');
    }
    builder.append("}\n\n");
  }

  private List<TsProperty> properties(Class<?> type, Direction direction) {
    Direction primaryView = direction == Direction.REQUEST ? Direction.REQUEST : Direction.RESPONSE;
    BeanDescription bean = introspect(type, primaryView);
    requireSupportedShape(type, bean);
    JsonSerialize classSerializer = bean.getClassInfo().getAnnotation(JsonSerialize.class);
    boolean presenceSerialized =
        classSerializer != null && PRESENCE_SERIALIZERS.contains(classSerializer.using());
    Set<String> visible = new LinkedHashSet<>();
    List<TsProperty> properties = new ArrayList<>();
    for (BeanPropertyDefinition property : bean.findProperties()) {
      if (!visibleInDirection(primaryView, property)) {
        continue;
      }
      String name = property.getName();
      if (!visible.add(name)) {
        throw new IllegalStateException("duplicate property " + name + " on " + javaName(type));
      }
      requireSupportedProperty(type, property);
      JavaType javaType = declaredType(property);
      boolean nullable = !javaType.isPrimitive() && isNullable(property);
      boolean optional =
          presenceSerialized
              || nullable
                  && (direction == Direction.REQUEST
                      || inclusion(bean, property) != JsonInclude.Include.ALWAYS);
      String stringType = stringSerializedType(property, javaType);
      properties.add(
          new TsProperty(
              name,
              stringType != null ? stringType : tsType(javaType, direction),
              optional,
              nullable));
    }
    if (direction == Direction.BOTH) {
      Set<String> reverse = new LinkedHashSet<>();
      for (BeanPropertyDefinition property : introspect(type, Direction.REQUEST).findProperties()) {
        if (visibleInDirection(Direction.REQUEST, property)) {
          reverse.add(property.getName());
        }
      }
      if (!reverse.equals(visible)) {
        throw new IllegalStateException(
            javaName(type)
                + " serialization properties "
                + visible
                + " differ from deserialization properties "
                + reverse);
      }
    }
    return properties;
  }

  /**
   * 属性的语义类型：有字段时取字段声明类型。严格请求 DTO 常用接收 {@code Object} 的 setter 自行校验 token 类型，setter 参数不代表 wire 形态。
   */
  private static JavaType declaredType(BeanPropertyDefinition property) {
    AnnotatedField field = property.getField();
    return field != null ? field.getType() : property.getPrimaryType();
  }

  /** 字段或 record 组件上的 {@link Nullable} 类型注解。 */
  private static boolean isNullable(BeanPropertyDefinition property) {
    AnnotatedField field = property.getField();
    if (field != null && field.getAnnotated() instanceof Field reflected) {
      return reflected.getAnnotatedType().isAnnotationPresent(Nullable.class);
    }
    AnnotatedParameter parameter = property.getConstructorParameter();
    if (parameter != null) {
      Class<?> owner = parameter.getDeclaringClass();
      if (owner.isRecord()) {
        for (RecordComponent component : owner.getRecordComponents()) {
          if (component.getName().equals(property.getInternalName())) {
            AnnotatedType annotated = component.getAnnotatedType();
            return annotated.isAnnotationPresent(Nullable.class);
          }
        }
      }
    }
    return false;
  }

  private void requireSupportedShape(Class<?> type, BeanDescription bean) {
    AnnotatedClass classInfo = bean.getClassInfo();
    JsonSerialize classSerializer = classInfo.getAnnotation(JsonSerialize.class);
    if (classSerializer != null
        && !isDefaultAnnotation(classSerializer, JSON_SERIALIZE_MEMBERS)
        && !isAllowlisted(classSerializer, PRESENCE_SERIALIZERS)) {
      throw new IllegalStateException(javaName(type) + " declares a class-level @JsonSerialize");
    }
    JsonFormat classFormat = classInfo.getAnnotation(JsonFormat.class);
    if (classFormat != null && !isDefaultAnnotation(classFormat, JSON_FORMAT_MEMBERS)) {
      throw new IllegalStateException(javaName(type) + " declares a class-level @JsonFormat");
    }
    if (bean.findJsonValueAccessor() != null && !type.isEnum()) {
      throw new IllegalStateException(javaName(type) + " declares @JsonValue");
    }
    if (bean.findAnyGetter() != null) {
      throw new IllegalStateException(javaName(type) + " declares @JsonAnyGetter");
    }
  }

  private void requireSupportedProperty(Class<?> type, BeanPropertyDefinition property) {
    for (AnnotatedMember member : members(property)) {
      JsonSerialize serializer = member.getAnnotation(JsonSerialize.class);
      if (serializer != null
          && !isDefaultAnnotation(serializer, JSON_SERIALIZE_MEMBERS)
          && !isAllowlisted(serializer, STRING_SERIALIZERS)) {
        throw new IllegalStateException(
            javaName(type) + "." + property.getName() + " declares a custom @JsonSerialize");
      }
      JsonFormat format = member.getAnnotation(JsonFormat.class);
      if (format != null && !isDefaultAnnotation(format, JSON_FORMAT_MEMBERS)) {
        throw new IllegalStateException(
            javaName(type) + "." + property.getName() + " declares a @JsonFormat customization");
      }
      if (member.getAnnotation(JsonUnwrapped.class) != null) {
        throw new IllegalStateException(
            javaName(type) + "." + property.getName() + " declares @JsonUnwrapped");
      }
    }
  }

  /** 白名单序列化器只能经 {@code using}/{@code contentUsing} 声明，其余成员保持默认。 */
  private static boolean isAllowlisted(JsonSerialize serializer, Set<Class<?>> allowlist) {
    List<String> customized = new ArrayList<>(2);
    if (allowlist.contains(serializer.using())) {
      customized.add("using");
    }
    if (allowlist.contains(serializer.contentUsing())) {
      customized.add("contentUsing");
    }
    List<String> rest = new ArrayList<>(JSON_SERIALIZE_MEMBERS);
    rest.removeAll(customized);
    return !customized.isEmpty() && isDefaultAnnotation(serializer, rest);
  }

  /** 白名单字符串序列化器改写后的 TS 类型：标量为 {@code string}，集合与 Map 改写元素；未声明时为 null。 */
  private static @Nullable String stringSerializedType(
      BeanPropertyDefinition property, JavaType type) {
    for (AnnotatedMember member : members(property)) {
      JsonSerialize serializer = member.getAnnotation(JsonSerialize.class);
      if (serializer == null) {
        continue;
      }
      if (STRING_SERIALIZERS.contains(serializer.using())) {
        return "string";
      }
      if (STRING_SERIALIZERS.contains(serializer.contentUsing())) {
        if (type.isMapLikeType() && type.getKeyType().getRawClass() == String.class) {
          return "Record<string, string>";
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
          return "string[]";
        }
        throw new IllegalStateException(
            property.getName() + " declares contentUsing on a non-container type: " + type);
      }
    }
    return null;
  }

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

  private BeanDescription introspect(Class<?> javaType, Direction view) {
    JavaType type = mapper.constructType(javaType);
    ClassIntrospector introspector =
        (view == Direction.REQUEST ? mapper.deserializationConfig() : mapper.serializationConfig())
            .classIntrospectorInstance();
    var classDef = introspector.introspectClassAnnotations(type);
    return view == Direction.REQUEST
        ? introspector.introspectForDeserialization(type, classDef)
        : introspector.introspectForSerialization(type, classDef);
  }

  private static boolean visibleInDirection(Direction view, BeanPropertyDefinition property) {
    return view == Direction.REQUEST ? property.couldDeserialize() : property.couldSerialize();
  }

  /** 序列化方向的 presence：属性级优先，其次类级，最后 mapper 全局默认；只支持 ALWAYS 与 NON_NULL。 */
  private JsonInclude.Include inclusion(BeanDescription bean, BeanPropertyDefinition property) {
    JsonInclude.Include resolved = null;
    for (AnnotatedMember member : members(property)) {
      JsonInclude annotation = member.getAnnotation(JsonInclude.class);
      if (annotation != null && annotation.value() != JsonInclude.Include.USE_DEFAULTS) {
        resolved = annotation.value();
        break;
      }
    }
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
          "unsupported @JsonInclude " + resolved + " on " + property.getName());
    }
    return resolved;
  }

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

  /**
   * 属性的 TS 类型。Long 写出为十进制字符串，读取则接受 JSON 整数（严格 setter 只接受整数），因此只读入的请求类型写作 {@code
   * number}，其余方向保持写出形态，客户端可以把收到的值原样回传。
   */
  private String tsType(JavaType type, Direction direction) {
    Class<?> raw = type.getRawClass();
    if (isContractType(raw)) {
      return tsName(raw);
    }
    if (direction == Direction.REQUEST && (raw == Long.class || raw == long.class)) {
      return "number";
    }
    for (ScalarSpec spec : SCALARS) {
      if (spec.javaType() == raw) {
        return spec.tsType();
      }
    }
    if (JsonNode.class.isAssignableFrom(raw) || raw == Object.class) {
      return "unknown";
    }
    if (type.isArrayType() || type.isCollectionLikeType()) {
      String element = tsType(type.getContentType(), direction);
      return element.matches("[A-Za-z0-9_]+") ? element + "[]" : "(" + element + ")[]";
    }
    if (type.isMapLikeType()) {
      if (type.getKeyType().getRawClass() != String.class) {
        throw new IllegalArgumentException("only string-keyed maps are supported: " + type);
      }
      return "Record<string, " + tsType(type.getContentType(), direction) + ">";
    }
    throw new IllegalArgumentException("unsupported wire type: " + type);
  }

  private static boolean isPolymorphic(Class<?> type) {
    return type.isInterface() && type.isAnnotationPresent(JsonTypeInfo.class);
  }

  private static boolean isSubtypeOfPolymorphic(Class<?> type) {
    for (Class<?> parent : type.getInterfaces()) {
      if (isPolymorphic(parent)) {
        return true;
      }
    }
    return false;
  }

  private static List<JsonSubTypes.Type> subtypes(Class<?> type) {
    JsonSubTypes annotation = type.getAnnotation(JsonSubTypes.class);
    if (annotation == null || annotation.value().length == 0) {
      throw new IllegalStateException(javaName(type) + " must declare @JsonSubTypes");
    }
    List<JsonSubTypes.Type> subtypes = List.of(annotation.value());
    Set<Class<?>> declared = new LinkedHashSet<>();
    for (JsonSubTypes.Type subtype : subtypes) {
      if (subtype.name().isEmpty() || !declared.add(subtype.value())) {
        throw new IllegalStateException(
            javaName(type) + " declares an unnamed or repeated subtype");
      }
    }
    Class<?>[] permitted = type.getPermittedSubclasses();
    if (permitted == null || !declared.equals(new LinkedHashSet<>(List.of(permitted)))) {
      throw new IllegalStateException(
          javaName(type) + " @JsonSubTypes must list exactly its permitted subclasses");
    }
    return subtypes;
  }

  /**
   * TS 名：顶层类型用 Java 简单名；嵌套类型用外层名（去掉 DTO 后缀）加自身名，例如 {@code PluginAuthKindDTO.DeepLink} 为 {@code
   * PluginAuthKindDeepLink}。
   */
  static String tsName(Class<?> type) {
    Class<?> enclosing = type.getEnclosingClass();
    if (enclosing == null) {
      return type.getSimpleName();
    }
    String outer = tsName(enclosing);
    if (outer.endsWith("DTO")) {
      outer = outer.substring(0, outer.length() - 3);
    }
    return outer + type.getSimpleName();
  }

  private static String constantName(String tsName) {
    return tsName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  private static String propertyName(String name) {
    return TYPESCRIPT_IDENTIFIER.matcher(name).matches() ? name : quote(name);
  }

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
                "cannot emit a TypeScript string literal containing U+%04X", (int) character));
      } else {
        quoted.append(character);
      }
    }
    return quoted.append('\'').toString();
  }

  private static String javaName(Class<?> type) {
    return type.getName().replace('$', '.');
  }

  private String tsType(Type type, Direction direction) {
    if (type == void.class || type == Void.class) {
      return "void";
    }
    if (type instanceof TypeVariable<?> variable) {
      return variable.getName();
    }
    if (type instanceof ParameterizedType pt) {
      Type raw = pt.getRawType();
      if (raw == Page.class) {
        Type arg = pt.getActualTypeArguments()[0];
        return "Page<" + tsType(arg, direction) + ">";
      }
      if (raw == List.class || raw == Collection.class || raw == Set.class) {
        Type arg = pt.getActualTypeArguments()[0];
        String elem = tsType(arg, direction);
        return elem.matches("[A-Za-z0-9_]+") ? elem + "[]" : "(" + elem + ")[]";
      }
      if (raw == Map.class) {
        Type val = pt.getActualTypeArguments()[1];
        return "Record<string, " + tsType(val, direction) + ">";
      }
    }
    return tsType(mapper.constructType(type), direction);
  }

  private static final Pattern PATH_VAR_PATTERN = Pattern.compile("\\{(\\*?[a-zA-Z0-9_$]+)\\}");

  private record RouteSpec(
      Method controllerMethod,
      String httpMethod,
      String path,
      Map<String, String> pathParams,
      Map<String, QueryParamSpec> queryParams,
      @Nullable BodySpec body,
      String responseType,
      @Nullable String responseTypeKind) {

    boolean hasPath() {
      return !pathParams.isEmpty();
    }

    boolean hasQuery() {
      return !queryParams.isEmpty();
    }

    boolean hasBody() {
      return body != null;
    }

    boolean hasResponseType() {
      return responseTypeKind != null;
    }

    String renderPath() {
      StringBuilder sb = new StringBuilder("{ ");
      boolean first = true;
      for (Map.Entry<String, String> entry : pathParams.entrySet()) {
        if (!first) {
          sb.append("; ");
        }
        first = false;
        sb.append(propertyName(entry.getKey())).append(": ").append(entry.getValue());
      }
      return sb.append(" }").toString();
    }

    String renderQuery() {
      StringBuilder sb = new StringBuilder("{ ");
      boolean first = true;
      for (Map.Entry<String, QueryParamSpec> entry : queryParams.entrySet()) {
        if (!first) {
          sb.append("; ");
        }
        first = false;
        sb.append(propertyName(entry.getKey()))
            .append(entry.getValue().optional() ? "?: " : ": ")
            .append(entry.getValue().tsType());
      }
      return sb.append(" }").toString();
    }
  }

  private record QueryParamSpec(String tsType, boolean optional) {}

  private record BodySpec(String tsType, boolean optional) {}

  private Map<String, RouteSpec> discoverRoutes() {
    Map<String, RouteSpec> routes = new TreeMap<>();
    for (Class<?> controller : controllers) {
      RequestMapping classMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      String[] classPaths = extractPaths(classMapping);
      Method[] methods = controller.getDeclaredMethods();
      Arrays.sort(
          methods, Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
      for (Method method : methods) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) {
          continue;
        }
        RequestMethod[] requestMethods = methodMapping.method();
        String[] methodPaths = extractPaths(methodMapping);
        for (String classPath : classPaths) {
          for (String methodPath : methodPaths) {
            String combinedPath = combinePaths(classPath, methodPath);
            if (!combinedPath.startsWith("/api/") && !combinedPath.equals("/api")) {
              continue;
            }
            if (requestMethods.length == 0) {
              throw new IllegalStateException(
                  "Route " + combinedPath + " on " + method + " has no HTTP method declared");
            }
            for (RequestMethod requestMethod : requestMethods) {
              String routeKey = requestMethod.name() + " " + combinedPath;
              RouteSpec routeSpec = buildRouteSpec(method, requestMethod.name(), combinedPath);
              RouteSpec previous = routes.put(routeKey, routeSpec);
              if (previous != null) {
                throw new IllegalStateException(
                    "duplicate route "
                        + routeKey
                        + " declared on "
                        + method
                        + " and "
                        + previous.controllerMethod());
              }
            }
          }
        }
      }
    }
    return routes;
  }

  private static String[] extractPaths(@Nullable RequestMapping mapping) {
    if (mapping == null) {
      return new String[] {""};
    }
    if (mapping.path().length > 0) {
      return mapping.path();
    }
    if (mapping.value().length > 0) {
      return mapping.value();
    }
    return new String[] {""};
  }

  static String combinePaths(String classPath, String methodPath) {
    String cp = classPath == null ? "" : classPath.trim();
    String mp = methodPath == null ? "" : methodPath.trim();
    if (!cp.isEmpty() && !cp.startsWith("/")) {
      cp = "/" + cp;
    }
    if (!mp.isEmpty() && !mp.startsWith("/")) {
      mp = "/" + mp;
    }
    if (cp.endsWith("/")) {
      cp = cp.substring(0, cp.length() - 1);
    }
    String combined = cp + mp;
    if (!combined.startsWith("/")) {
      combined = "/" + combined;
    }
    if (combined.length() > 1 && combined.endsWith("/")) {
      combined = combined.substring(0, combined.length() - 1);
    }
    StringBuilder normalized = new StringBuilder();
    for (int i = 0; i < combined.length(); i++) {
      char ch = combined.charAt(i);
      if (ch != '{') {
        normalized.append(ch);
        continue;
      }
      int colon = combined.indexOf(':', i);
      int end = combined.indexOf('}', i);
      if (end < 0) {
        throw new IllegalStateException("unclosed path variable in " + combined);
      }
      if (colon < 0 || end < colon) {
        normalized.append(combined, i, end + 1);
        i = end;
        continue;
      }
      normalized.append(combined, i, colon).append('}');
      int depth = 1;
      int cursor = colon + 1;
      while (cursor < combined.length() && depth > 0) {
        char part = combined.charAt(cursor++);
        if (part == '{') {
          depth++;
        } else if (part == '}') {
          depth--;
        }
      }
      if (depth != 0) {
        throw new IllegalStateException("unclosed path variable in " + combined);
      }
      i = cursor - 1;
    }
    return normalized.toString();
  }

  private RouteSpec buildRouteSpec(Method method, String httpMethod, String path) {
    Matcher matcher = PATH_VAR_PATTERN.matcher(path);
    Set<String> expectedVars = new LinkedHashSet<>();
    while (matcher.find()) {
      String raw = matcher.group(1);
      String varName = raw.startsWith("*") ? raw.substring(1) : raw;
      expectedVars.add(varName);
    }

    Map<String, Parameter> pathParamsByVar = new HashMap<>();
    for (Parameter parameter : method.getParameters()) {
      PathVariable pv = parameter.getAnnotation(PathVariable.class);
      if (pv != null) {
        String varName = resolvePathVariableName(parameter, pv);
        if (pathParamsByVar.put(varName, parameter) != null) {
          throw new IllegalStateException("duplicate @PathVariable " + varName + " on " + method);
        }
      }
    }

    for (String varName : expectedVars) {
      if (!pathParamsByVar.containsKey(varName)) {
        throw new IllegalStateException(
            "path "
                + path
                + " requires variable "
                + varName
                + " but no matching @PathVariable on "
                + method);
      }
    }
    for (String varName : pathParamsByVar.keySet()) {
      if (!expectedVars.contains(varName)) {
        throw new IllegalStateException(
            "@PathVariable " + varName + " on " + method + " does not exist in path " + path);
      }
    }

    Map<String, String> pathParams = new TreeMap<>();
    for (String varName : expectedVars) {
      Parameter p = pathParamsByVar.get(varName);
      pathParams.put(varName, tsType(p.getParameterizedType(), Direction.REQUEST));
    }

    Map<String, QueryParamSpec> queryParams = new TreeMap<>();
    for (Parameter parameter : method.getParameters()) {
      RequestParam rp = parameter.getAnnotation(RequestParam.class);
      if (rp == null) {
        continue;
      }
      String name = resolveParamName(parameter, rp);
      boolean optional =
          !rp.required()
              || !ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())
              || parameter.isAnnotationPresent(Nullable.class);
      String tsType = tsType(parameter.getParameterizedType(), Direction.REQUEST);
      if (queryParams.put(name, new QueryParamSpec(tsType, optional)) != null) {
        throw new IllegalStateException("duplicate @RequestParam " + name + " on " + method);
      }
    }

    BodySpec bodySpec = null;
    for (Parameter parameter : method.getParameters()) {
      RequestBody rb = parameter.getAnnotation(RequestBody.class);
      if (rb == null) {
        continue;
      }
      if (bodySpec != null) {
        throw new IllegalStateException("multiple @RequestBody on " + method);
      }
      boolean optional = !rb.required() || parameter.isAnnotationPresent(Nullable.class);
      String tsType = tsType(parameter.getParameterizedType(), Direction.REQUEST);
      bodySpec = new BodySpec(tsType, optional);
    }

    Type returnType = method.getGenericReturnType();
    Type unwrapped = unwrapResponseType(returnType);
    String responseTsType;
    String responseTypeKind = null;
    if (unwrapped == void.class || unwrapped == Void.class) {
      responseTsType = "void";
    } else if (unwrapped == byte[].class
        || unwrapped == InputStream.class
        || (unwrapped instanceof Class<?> cls && Resource.class.isAssignableFrom(cls))) {
      responseTsType = "Blob";
      responseTypeKind = "blob";
    } else if ((unwrapped == String.class || unwrapped == CharSequence.class)
        && Arrays.stream(
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class).produces())
            .anyMatch(value -> value.startsWith("text/"))) {
      responseTsType = "string";
      responseTypeKind = "text";
    } else {
      responseTsType = tsType(unwrapped, Direction.RESPONSE);
    }

    return new RouteSpec(
        method,
        httpMethod,
        path,
        pathParams,
        queryParams,
        bodySpec,
        responseTsType,
        responseTypeKind);
  }

  private static String resolveParamName(Parameter parameter, RequestParam annotation) {
    if (!annotation.name().isEmpty()) {
      return annotation.name();
    }
    if (!annotation.value().isEmpty()) {
      return annotation.value();
    }
    return parameter.getName();
  }

  private static String resolvePathVariableName(Parameter parameter, PathVariable annotation) {
    if (!annotation.name().isEmpty()) {
      return annotation.name();
    }
    if (!annotation.value().isEmpty()) {
      return annotation.value();
    }
    return parameter.getName();
  }

  static Type unwrapResponseType(Type type) {
    if (type instanceof ParameterizedType pt) {
      Type raw = pt.getRawType();
      if (raw == ApiResponse.class || raw == ResponseEntity.class) {
        Type[] args = pt.getActualTypeArguments();
        if (args.length > 0) {
          return unwrapResponseType(args[0]);
        }
        return void.class;
      }
    } else if (type instanceof Class<?> cls) {
      if (cls == ApiResponse.class || cls == ResponseEntity.class) {
        return void.class;
      }
    }
    return type;
  }

  /** 单个属性在 wire 上的 TS 投影。 */
  record TsProperty(String name, String tsType, boolean optional, boolean nullable) {}
}
