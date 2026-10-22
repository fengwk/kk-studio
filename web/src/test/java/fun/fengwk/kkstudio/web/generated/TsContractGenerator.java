package fun.fengwk.kkstudio.web.generated;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.regex.Pattern;

/**
 * 从 Web 控制器签名与生产 HTTP Jackson 3 mapper 生成全部传输契约的 TypeScript 类型。
 *
 * <p>根类型取自控制器处理方法的 {@code @RequestBody} 参数（请求方向）与返回值（响应方向），再加上嵌在错误响应上下文里的结构化载荷； 从根出发沿属性闭包收集契约包（生产为
 * {@code fun.fengwk.kkstudio.share}）下的 DTO 与枚举。属性名与顺序来自真实 mapper 的 introspection，枚举 wire 值由 mapper
 * 序列化每个常量得到，标量映射在构造时用探针核验。presence 规则：
 *
 * <ul>
 *   <li>未标 {@link Nullable} 的属性必填且非空；原始类型同理。
 *   <li>写出方向带 {@code @JsonInclude(ALWAYS)} 的可空属性必定出现：{@code T | null}。
 *   <li>其它可空属性可以缺省，消费方把缺省与 null 同等对待：{@code ?: T | null}。
 * </ul>
 *
 * <p>任何会改变写出形态的定制（类级或属性级序列化器、格式化、unwrapped、any getter、契约包外的对象类型）都固定失败，绝不回退成 {@code
 * any}。定制反序列化器只允许收紧输入，必须接受属性声明的形态。
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
  private final String contractPackage;
  private final List<Class<?>> controllers;
  private final List<Class<?>> errorPayloadTypes;
  private final Map<Class<?>, Direction> directions = new HashMap<>();
  private final Deque<Class<?>> pending = new ArrayDeque<>();

  TsContractGenerator(
      JsonMapper mapper,
      String contractPackage,
      List<Class<?>> controllers,
      List<Class<?>> errorPayloadTypes) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.contractPackage = Objects.requireNonNull(contractPackage, "contractPackage") + ".";
    this.controllers = List.copyOf(controllers);
    this.errorPayloadTypes = List.copyOf(errorPayloadTypes);
    validateWireProfile(mapper);
  }

  /** 渲染完整生成产物；同一组控制器与 mapper 必定产生逐字节一致的结果。 */
  String generate() {
    directions.clear();
    pending.clear();
    for (Class<?> controller : controllers) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        for (Parameter parameter : method.getParameters()) {
          if (parameter.isAnnotationPresent(RequestBody.class)) {
            visit(parameter.getParameterizedType(), Direction.REQUEST);
          }
        }
        visit(method.getGenericReturnType(), Direction.RESPONSE);
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
      if (type.isEnum()) {
        appendEnum(builder, type);
      } else if (isPolymorphic(type)) {
        appendUnion(builder, type);
      } else if (!isSubtypeOfPolymorphic(type)) {
        appendObject(builder, type, directions.get(type), null);
      }
    }
    return builder.toString().stripTrailing() + '\n';
  }

  private void visit(Type type, Direction direction) {
    if (type instanceof Class<?> raw) {
      if (raw.isArray()) {
        visit(raw.getComponentType(), direction);
        return;
      }
      if (raw.getName().startsWith(contractPackage)) {
        register(raw, direction);
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        visit(argument, direction);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      visit(array.getGenericComponentType(), direction);
    }
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
    if (type.isEnum()) {
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
    if (raw.getName().startsWith(contractPackage)) {
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

  /** 枚举：TS 联合类型 + 由 mapper 序列化真实常量得到的 readonly wire 值数组。 */
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
    for (Object constant : constants) {
      JsonNode node = mapper.readTree(mapper.writeValueAsString(constant));
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
          nullable
              && (direction == Direction.REQUEST
                  || inclusion(bean, property) != JsonInclude.Include.ALWAYS);
      properties.add(new TsProperty(name, tsType(javaType, direction), optional, nullable));
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
    if (classSerializer != null && !isDefaultAnnotation(classSerializer, JSON_SERIALIZE_MEMBERS)) {
      throw new IllegalStateException(javaName(type) + " declares a class-level @JsonSerialize");
    }
    JsonFormat classFormat = classInfo.getAnnotation(JsonFormat.class);
    if (classFormat != null && !isDefaultAnnotation(classFormat, JSON_FORMAT_MEMBERS)) {
      throw new IllegalStateException(javaName(type) + " declares a class-level @JsonFormat");
    }
    if (bean.findJsonValueAccessor() != null) {
      throw new IllegalStateException(javaName(type) + " declares @JsonValue");
    }
    if (bean.findAnyGetter() != null) {
      throw new IllegalStateException(javaName(type) + " declares @JsonAnyGetter");
    }
  }

  private void requireSupportedProperty(Class<?> type, BeanPropertyDefinition property) {
    for (AnnotatedMember member : members(property)) {
      JsonSerialize serializer = member.getAnnotation(JsonSerialize.class);
      if (serializer != null && !isDefaultAnnotation(serializer, JSON_SERIALIZE_MEMBERS)) {
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
    if (raw.getName().startsWith(contractPackage)) {
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

  /** 单个属性在 wire 上的 TS 投影。 */
  record TsProperty(String name, String tsType, boolean optional, boolean nullable) {}
}
