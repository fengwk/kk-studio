package fun.fengwk.kkstudio.platform.configsync;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置同步 YAML 的读写边界。
 *
 * <p>读取走 SnakeYAML {@link SafeConstructor}：只接受标准对象/数组/标量，重复键、递归键、集合别名、非法 tag 与任意 Java 类型一律拒绝；从不 由
 * YAML 构造任意对象。浮点标量构造为 {@link BigDecimal} 并拒绝 inf/nan，避免高精度数值被 Double 截断。每次读写都新建 reader/writer，
 * 不共享有状态的 {@link Yaml}。
 *
 * <p>嵌套结构到强类型 DTO 的转换使用独立的严格 mapper（未知字段、字符串→数值/布尔、数值→字符串等宽松转换全部失败）。转换前用 Jackson introspection 对照目标
 * Bean 的实际属性检测未知字段，不维护手写 Schema 副本；Map/数组的内容是动态的，不参与未知检测。
 */
@Component
public final class ConfigSyncYaml {

  private static final String RESOURCE = "config_sync";
  private static final int MAX_CODE_POINTS = 8 * 1024 * 1024;
  private static final int NESTING_DEPTH_LIMIT = 64;

  private final ObjectMapper strictMapper;

  public ConfigSyncYaml() {
    JsonMapper mapper =
        JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();
    // 契约要求严格类型：不做字符串→数值/布尔/小数、浮点→整数或任意标量→字符串的隐式转换。
    mapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Float)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Boolean)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    this.strictMapper = mapper;
  }

  /** 解析 YAML 文档顶层为纯字符串键 Map；任何结构或类型问题都作为导入错误抛出。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> parse(String yaml) {
    if (yaml == null || yaml.isBlank()) {
      throw new AiValidationException(RESOURCE, "config yaml must not be blank");
    }
    if (yaml.length() > MAX_CODE_POINTS) {
      throw new AiValidationException(RESOURCE, "config yaml is too large");
    }
    Object root;
    try {
      root = newReader().load(yaml);
    } catch (RuntimeException error) {
      // 不回显 SnakeYAML 的错误文本：其中可能包含 YAML 片段或键值。
      throw new AiValidationException(RESOURCE, "config yaml is not a valid YAML document");
    }
    if (!(root instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      throw new AiValidationException(RESOURCE, "config yaml root must be a non-empty object");
    }
    Map<String, Object> document = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new AiValidationException(RESOURCE, "config yaml keys must be strings");
      }
      document.put(key, normalize(entry.getValue(), key));
    }
    return document;
  }

  /** 将纯结构文档输出为 YAML 文本；不产生 Java tag。 */
  public String dump(Map<String, Object> document) {
    return newWriter().dump(document);
  }

  /** 把已解析的纯结构节点转换为强类型 DTO；失败信息只包含路径，不回显值。 */
  public <T> T convert(Object node, Class<T> type, String path) {
    if (node == null) {
      throw new AiValidationException(RESOURCE, path + " must not be null");
    }
    try {
      return strictMapper.convertValue(node, type);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, path + " is not a valid value");
    }
  }

  /** 供需要序列化 DTO 到纯结构的调用方复用同一个严格 mapper 的写能力。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> toMap(Object value, String path) {
    try {
      return strictMapper.convertValue(value, LinkedHashMap.class);
    } catch (IllegalArgumentException | ClassCastException error) {
      throw new IllegalStateException("cannot serialize " + path, error);
    }
  }

  /**
   * 按目标 DTO 的实际 Bean 属性递归剔除未声明字段，返回剔除后的纯结构副本。
   *
   * <p>Map 的值与集合/数组的元素按其声明的内容类型递归；未声明内容类型（如 {@code Object}）视为动态结构不检查。被剔除字段的路径写入 {@code
   * removed}，供调用方决定是整体跳过条目还是合并其余字段。
   */
  public Object removeUnknownProperties(
      Class<?> type, Object node, String path, List<String> removed) {
    return clean(strictMapper.constructType(type), node, path, removed);
  }

  private Yaml newReader() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setAllowRecursiveKeys(false);
    // 禁止集合别名：递归 YAML 结构与 alias 放大在解析期即被拒绝，normalize 不会无限递归。
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(NESTING_DEPTH_LIMIT);
    options.setCodePointLimit(MAX_CODE_POINTS);
    return new Yaml(new StrictConstructor(options));
  }

  private static Yaml newWriter() {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setSplitLines(false);
    return new Yaml(options);
  }

  private Object normalize(Object value, String path) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number) {
      return value;
    }
    if (value instanceof Map<?, ?> rawMap) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new AiValidationException(RESOURCE, path + " keys must be strings");
        }
        normalized.put(key, normalize(entry.getValue(), path + "." + key));
      }
      return normalized;
    }
    if (value instanceof List<?> rawList) {
      List<Object> normalized = new ArrayList<>(rawList.size());
      for (int index = 0; index < rawList.size(); index++) {
        normalized.add(normalize(rawList.get(index), path + "[" + index + "]"));
      }
      return normalized;
    }
    // SafeConstructor 理论上不会产生其它类型；出现即视为非法输入。
    throw new AiValidationException(RESOURCE, path + " has an unsupported YAML value");
  }

  private Object clean(JavaType type, Object node, String path, List<String> removed) {
    if (node == null) {
      return null;
    }
    Class<?> raw = type.getRawClass();
    if (raw == Object.class || isScalar(raw)) {
      return node;
    }
    if (Map.class.isAssignableFrom(raw)) {
      if (!(node instanceof Map<?, ?> map)) {
        return node;
      }
      JavaType valueType = type.getContentType();
      Map<String, Object> cleaned = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        cleaned.put(
            key,
            valueType == null
                ? entry.getValue()
                : clean(valueType, entry.getValue(), path + "." + key, removed));
      }
      return cleaned;
    }
    if (Collection.class.isAssignableFrom(raw)) {
      if (!(node instanceof Collection<?> collection)) {
        return node;
      }
      JavaType elementType = type.getContentType();
      List<Object> cleaned = new ArrayList<>(collection.size());
      int index = 0;
      for (Object element : collection) {
        cleaned.add(
            elementType == null
                ? element
                : clean(elementType, element, path + "[" + index + "]", removed));
        index++;
      }
      return cleaned;
    }
    if (!(node instanceof Map<?, ?> map)) {
      return node;
    }
    Map<String, BeanPropertyDefinition> properties = new LinkedHashMap<>();
    BeanDescription description = strictMapper.getDeserializationConfig().introspect(type);
    for (BeanPropertyDefinition property : description.findProperties()) {
      if (property.couldDeserialize()) {
        properties.put(property.getName(), property);
      }
    }
    Map<String, Object> cleaned = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = String.valueOf(entry.getKey());
      BeanPropertyDefinition property = properties.get(key);
      if (property == null) {
        removed.add(path + "." + key);
        continue;
      }
      JavaType propertyType = property.getPrimaryType();
      cleaned.put(
          key,
          propertyType == null
              ? entry.getValue()
              : clean(propertyType, entry.getValue(), path + "." + key, removed));
    }
    return cleaned;
  }

  private static boolean isScalar(Class<?> raw) {
    return raw.isPrimitive()
        || raw.isEnum()
        || raw == String.class
        || raw == Boolean.class
        || Number.class.isAssignableFrom(raw);
  }

  /** 覆盖 SafeConstructor：浮点标量构造为 BigDecimal，inf/nan 直接拒绝。 */
  private static final class StrictConstructor extends SafeConstructor {

    StrictConstructor(LoaderOptions options) {
      super(options);
      this.yamlConstructors.put(Tag.FLOAT, new BigDecimalFloatConstruct());
    }
  }

  private static final class BigDecimalFloatConstruct extends AbstractConstruct {

    @Override
    public Object construct(Node node) {
      String raw = ((ScalarNode) node).getValue();
      String value = raw.replace("_", "").toLowerCase(Locale.ROOT);
      if (value.contains("inf") || value.contains("nan")) {
        throw new YAMLException("non-finite float is not allowed");
      }
      try {
        return new BigDecimal(value);
      } catch (NumberFormatException error) {
        throw new YAMLException("invalid float value");
      }
    }
  }
}
